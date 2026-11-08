package com.aittest.generation.orchestrator;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmMessage;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.aicore.log.ModelPriceCache;
import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.structured.JsonRepairer;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.testcase.dto.CaseDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用例生成的<b>编排层</b>：只做 LLM 相关的事——渲染 → 调用 → 解析 → 返修 → 判定档位 → 交回草稿。
 *
 * <p><b>为什么从 {@code CaseGenerateHandler} 里抽出来</b>（用户已拍板）：handler 原来一手包了
 * "查库 + 调模型 + 落库"，三件事的变更频率差一个量级——表结构/索引随业务改，三档降级的判定
 * 随模型质量改，落库的幂等口径随并发事故改。混在一个 600 行的方法里，任何一处的改动都要
 * 重读全部。切开之后两条边界非常硬：</p>
 * <ul>
 *   <li><b>本类零 DB 依赖</b>：构造函数里没有任何 {@code Mapper}，也就没有任何事务、
 *       没有 {@code transaction} 注解、没有幂等 SQL。想在这里"顺手补一次查询"都做不到。</li>
 *   <li><b>handler 零模型细节</b>：它不知道围栏、尾逗号、schema、返修轮次、置信度档位，
 *       只看到一个 {@link GenerationResult}。</li>
 * </ul>
 *
 * <p><b>三档语义</b>（D-03 / D-39，判定规则见 {@link DegradePolicy}）：</p>
 * <ol>
 *   <li><b>NORMAL</b>：{@code parseArrayWithMeta} 一次通过。<b>本地 {@link JsonRepairer} 修过的
 *       仍算 NORMAL</b>——去掉围栏/尾逗号不改语义，把它算成降级会让"降级率"变成一个吓人的假数；</li>
 *   <li><b>REPAIRED</b>：本地修不好、schema 仍不合规 → 用 {@code case_repair} 模板让模型改。
 *       最多 {@link #MAX_REPAIR_ROUNDS} 轮；成功即 {@code degraded=0}、{@code confidence=1.000}；</li>
 *   <li><b>LENIENT</b>：返修用完仍不合规，但<b>部分元素合规</b> → 逐元素筛选后落库，
 *       {@code degraded=1}、{@code confidence=0.500}。一条都不合规则由 handler 判失败。</li>
 * </ol>
 *
 * <p><b>两条刻意的"不发请求"捷径</b>：</p>
 * <ul>
 *   <li><b>截断不返修</b>：输出被 {@code max_tokens} 截断时，"丢掉半个元素、闭合数组"是
 *       <b>确定性</b>动作，本地就能做对（{@code case_repair} 提示词第 4 条也明令模型
 *       <b>不许</b>凭空补元素）。再发一轮请求只会花钱又给模型一次编造的机会，
 *       所以直接进 LENIENT；</li>
 *   <li><b>同样的错不重生</b>：连续两轮的 {@code validation_error} 归一化后完全一致，
 *       说明模型没在听（或它根本没有能力修），再问第三遍是白烧钱，提前结束。</li>
 * </ul>
 *
 * <p><b>上游失败一律冒泡</b>：{@link LlmException}（除 {@code E_SCHEMA_INVALID} 外）不在这里
 * try/catch。重试、退避、置 FAILED 是 {@code GenTaskWorker} 的职责，本类接住它只会让
 * "重试 3 次"变成"一次就定终身"。</p>
 *
 * @author 寇豆码
 */
@Service
public class CaseGenerateOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CaseGenerateOrchestrator.class);

    /** 生成模板编码（{@code prompts/case_generate.v1.md} 的 front-matter {@code code}）。 */
    private static final String GENERATE_CODE = "case_generate";

    /** 返修模板编码（{@code prompts/case_repair.v1.md}）。 */
    private static final String REPAIR_CODE = "case_repair";

    /**
     * 返修调用的场景名。
     *
     * <p><b>为什么是字面量而不是 {@code TaskScene}</b>：{@code TaskScene} 描述的是
     * <b>任务</b>场景（一个任务只能是抽点或生成），而 {@code CASE_REPAIR} 是<b>一次调用</b>的
     * 场景。把它塞进 {@code TaskScene} 会让人以为能提交一个"返修任务"。
     * 它只落 {@code llm_call_log.scene}，与 {@code PromptTemplate.scene} 的取值一致。</p>
     */
    private static final String REPAIR_SCENE = "CASE_REPAIR";

    /**
     * 模型返修的最大轮次（<b>不是 3</b>）。
     *
     * <p>返修是"再花一次钱"，不是"多试几次"：{@code ai.maxRetry=3} 是<b>整条 item</b> 的
     * 重试上限（由 worker 管，含退避），与"一次输出内返修几轮"是两个量纲。
     * 2 轮的根据是实测：第一轮能修好的占绝大多数，第二轮还修不好的，第三轮也修不好
     * （错误没变说明模型没能力改，错误变了说明它在瞎猜）。</p>
     */
    private static final int MAX_REPAIR_ROUNDS = 2;

    /**
     * 塞进返修提示词的"上一次原始输出"上限（字符）。
     *
     * <p>返修提示词要同时装原输出 + 校验错误 + 整份 schema（{@code testcase.schema.json} 约 2KB）。
     * 满 20 条用例的合法输出也就 6~8KB，8000 足够；但模型跑飞时可能吐几十 KB 的重复内容，
     * 不设上限会把上下文撑爆，反而让这次返修必然失败。</p>
     */
    private static final int REPAIR_OUTPUT_MAX = 8000;

    /** {@link JsonSchemaValidator#validate} 给 {@code E_SCHEMA_INVALID} 消息加的前缀（归一化时剥掉）。 */
    private static final String SCHEMA_ERROR_PREFIX = "模型输出未通过结构校验：";

    /** 模板查询（生成 + 返修两个模板）。 */
    private final PromptTemplateService promptTemplateService;

    /** 提示词渲染（缺变量会抛 40001，这是刻意的）。 */
    private final PromptRenderer promptRenderer;

    /** 记账装饰器（必须走它，否则这次调用不进成本台账）。 */
    private final LoggingLlmProvider loggingLlmProvider;

    /** schema 读取、整体校验、元素级筛选。 */
    private final JsonSchemaValidator jsonSchemaValidator;

    /** 结构化解析（修复 → 解包 → 校验，以及宽松档的只解包不校验）。 */
    private final StructuredOutputParser structuredOutputParser;

    /** JSON 转换（校验通过的节点 → {@link CaseDraft}）。 */
    private final ObjectMapper objectMapper;

    /** 温度配置：生成与返修<b>都用 {@code generate}</b>（返修不降到 0，理由见调用点注释）。 */
    private final AiProperties aiProperties;

    /** 成本换算（token → 微元）。 */
    private final ModelPriceCache modelPriceCache;

    public CaseGenerateOrchestrator(PromptTemplateService promptTemplateService,
                                    PromptRenderer promptRenderer,
                                    LoggingLlmProvider loggingLlmProvider,
                                    JsonSchemaValidator jsonSchemaValidator,
                                    StructuredOutputParser structuredOutputParser,
                                    ObjectMapper objectMapper,
                                    AiProperties aiProperties,
                                    ModelPriceCache modelPriceCache) {
        this.promptTemplateService = promptTemplateService;
        this.promptRenderer = promptRenderer;
        this.loggingLlmProvider = loggingLlmProvider;
        this.jsonSchemaValidator = jsonSchemaValidator;
        this.structuredOutputParser = structuredOutputParser;
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        this.modelPriceCache = modelPriceCache;
    }

    /**
     * 跑完一次"测试点 → 用例草稿"的生成（含三档降级）。
     *
     * @param context 本次生成的纯输入
     * @return 草稿 + 累计指标 + 档位（{@code drafts} 可能为空，见 {@link GenerationResult}）
     * @throws LlmException 上游/语法类失败（{@code E_SCHEMA_INVALID} 除外，它走返修与降级）
     */
    public GenerationResult generate(GenerationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("GenerationContext 不能为 null");
        }
        long startNanos = System.nanoTime();
        Ledger ledger = new Ledger();

        // 1) 模板：schema 也从这里拿。让调用方传 schema 会出现
        //    "handler 按 A 模板取 schema、编排层按 B 模板渲染"的错配。
        PromptTemplate generateTemplate = promptTemplateService.activeOf(GENERATE_CODE);
        String schemaPath = generateTemplate.getOutputSchema();
        String schemaText = schemaTextOf(schemaPath);
        String rendered = promptRenderer.render(generateTemplate.getContent(), renderVars(context));

        // 2) 主调用：attemptNo 跟着 item 的重试次数走（首次通过率的唯一来源）
        LlmResponse response = call(context, rendered, schemaText,
                TaskScene.CASE_GENERATE.code(), aiProperties.getTemperature().getGenerate(), ledger);
        String raw = contentOf(response);

        // 3) 主解析：本地修复 → 解包 → 整体 schema 校验
        ArrayNode items = null;
        String schemaError = null;
        boolean truncated = false;
        boolean localRepaired = false;
        try {
            StructuredOutputParser.ParseResult parsed =
                    structuredOutputParser.parseArrayWithMeta(raw, schemaPath);
            items = parsed.items();
            truncated = parsed.truncated();
            localRepaired = parsed.repaired();
        } catch (LlmException ex) {
            if (ex.getErrorCode() != ErrorCode.E_SCHEMA_INVALID) {
                // 语法都修不好 / 上游异常：不是本类能救的，交给 worker 退避重试
                throw ex;
            }
            schemaError = normalizeValidationError(ex.getMessage());
            // 校验抛异常时拿不到 ParseResult 的元信息，只能再问一次修复器（纯函数、无副作用）：
            // 截断与"本地修过"决定了下一步是打捞还是返修
            JsonRepairer.RepairOutcome meta = JsonRepairer.repairWithMeta(raw);
            truncated = meta.truncated();
            localRepaired = meta.changed();
        }

        // 4) 截断：不发第二轮请求，直接元素级打捞（见类注释"两条捷径"）
        if (truncated) {
            log.warn("[CASE] 主输出被截断，跳过返修直接打捞 itemId={} 主解析成功={}",
                    context.taskItemId(), items != null);
            return lenient(context, raw, schemaPath, true, 0, schemaError, localRepaired, ledger, startNanos);
        }

        // 5) 一次通过（含本地语法修复）→ NORMAL
        if (items != null) {
            List<CaseDraft> drafts = toDrafts(items);
            if (drafts.isEmpty()) {
                // 通过校验的空数组：只可能出现在模板没配 output_schema 时（配了的话 minItems:1 会先拦掉）
                return build(drafts, new DegradePolicy.Verdict(DegradeLevel.NORMAL,
                        "正常档：模型输出通过结构校验，但数组为空"), false, 0, ledger, startNanos);
            }
            DegradePolicy.Verdict verdict = DegradePolicy.decide(
                    localRepaired, false, response != null && response.isDegraded(), 0, drafts.size());
            return build(drafts, verdict, false, 0, ledger, startNanos);
        }

        // 6) 返修档：把"原输出 + 归一化后的校验错误 + schema"回给模型
        String lastRaw = raw;
        String lastError = schemaError;
        int rounds = 0;
        ArrayNode repairedItems = null;
        boolean repairedTruncated = false;
        while (rounds < MAX_REPAIR_ROUNDS) {
            rounds++;
            repairedItems = null;
            repairedTruncated = false;
            PromptTemplate repairTemplate = promptTemplateService.activeOf(REPAIR_CODE);
            // 返修的 {{schema}} 与 request.jsonSchema 都用**主模板**的 schema 原文：
            // 校验用的就是 schemaPath，若返修模板自带另一个 output_schema，就会出现
            // "让模型按 A 修、我们按 B 校验"的错配，返修必然失败还查不出原因。
            String repairPrompt = promptRenderer.render(repairTemplate.getContent(), Map.of(
                    "original_output", truncate(lastRaw, REPAIR_OUTPUT_MAX),
                    "validation_error", nullToEmpty(lastError),
                    "schema", schemaText));
            // 温度用 generate 的同值（不调到 0）：修复靠"看到具体错误后改对"，
            // 降低随机性不会让它更准，反而容易让模型原样重复同一个错误
            LlmResponse repairResponse = call(context, repairPrompt, schemaText,
                    REPAIR_SCENE, aiProperties.getTemperature().getGenerate(), ledger);
            String repairRaw = contentOf(repairResponse);
            try {
                StructuredOutputParser.ParseResult parsed =
                        structuredOutputParser.parseArrayWithMeta(repairRaw, schemaPath);
                repairedItems = parsed.items();
                repairedTruncated = parsed.truncated();
                break;
            } catch (LlmException ex) {
                if (ex.getErrorCode() != ErrorCode.E_SCHEMA_INVALID) {
                    throw ex;
                }
                String nextError = normalizeValidationError(ex.getMessage());
                lastRaw = repairRaw;
                if (nextError.equals(lastError)) {
                    log.warn("[CASE] 本轮校验错误与上一轮完全一致，提前结束返修 itemId={} 轮次={} 错误={}",
                            context.taskItemId(), rounds, nextError);
                    break;
                }
                lastError = nextError;
            }
        }

        if (repairedItems != null && !repairedTruncated) {
            List<CaseDraft> drafts = toDrafts(repairedItems);
            DegradePolicy.Verdict verdict = DegradePolicy.decide(false, false, false, rounds, drafts.size());
            log.info("[CASE] 返修成功 itemId={} 轮次={} 用例={}", context.taskItemId(), rounds, drafts.size());
            return build(drafts, verdict, false, rounds, ledger, startNanos);
        }
        if (repairedItems != null) {
            // 返修通了但被截断：D-03 里截断一律降级，不能因为"它是返修产出的"就抬成修复档
            List<CaseDraft> drafts = toDrafts(repairedItems);
            DegradePolicy.Verdict verdict = DegradePolicy.decide(false, true, true, rounds, drafts.size());
            log.warn("[CASE] 返修输出被截断，按宽松档记 itemId={} 轮次={} 用例={}",
                    context.taskItemId(), rounds, drafts.size());
            return build(drafts, verdict, true, rounds, ledger, startNanos);
        }

        // 7) 返修用完仍不合规 → 元素级打捞（用最后一次的输出：它离"修好"最近）
        return lenient(context, lastRaw, schemaPath, false, rounds, lastError, false, ledger, startNanos);
    }

    // ------------------------------------------------------------------ 内部步骤

    /**
     * 宽松档：解包 → 逐元素筛 → 判定档位。
     *
     * @param context       本次生成上下文
     * @param raw           待打捞的原始输出（截断时是主输出，返修失败时是最后一轮返修输出）
     * @param schemaPath    schema 路径
     * @param truncated     是否因为截断走到这里（返修用完后的打捞为 {@code false}）
     * @param rounds        已用掉的返修轮次
     * @param reasonHint    导致落到宽松档的校验错误（只用于日志）
     * @param localRepaired 本地修复器是否改动过原始输出
     * @param ledger        指标台账
     * @param startNanos    计时起点
     * @return 结果（{@code drafts} 为空表示一条都没救回来，由调用方判失败）
     */
    private GenerationResult lenient(GenerationContext context, String raw, String schemaPath,
                                     boolean truncated, int rounds, String reasonHint,
                                     boolean localRepaired, Ledger ledger, long startNanos) {
        ArrayNode salvaged = salvage(raw, schemaPath);
        List<CaseDraft> drafts = toDrafts(salvaged);
        DegradePolicy.Verdict verdict = DegradePolicy.decide(localRepaired, truncated, true, rounds, drafts.size());
        if (drafts.isEmpty()) {
            log.warn("[CASE] 宽松档无可用用例 itemId={} 截断={} 返修轮次={} 校验错误={}",
                    context.taskItemId(), truncated, rounds, reasonHint);
        } else {
            log.warn("[CASE] 宽松档保住 {} 条用例 itemId={} 截断={} 返修轮次={} 原始条目数={}",
                    drafts.size(), context.taskItemId(), truncated, rounds, salvaged.size());
        }
        return build(drafts, verdict, truncated, rounds, ledger, startNanos);
    }

    /**
     * 元素级打捞：解包成数组，再逐元素过 schema。
     *
     * @param raw        原始输出
     * @param schemaPath schema 路径
     * @return 合规元素组成的数组（可能为空）
     */
    private ArrayNode salvage(String raw, String schemaPath) {
        ArrayNode out = objectMapper.createArrayNode();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        ArrayNode array;
        try {
            array = structuredOutputParser.unwrapArray(raw);
        } catch (LlmException ex) {
            // 连数组都解不出来（例如只有半个开括号）：没有可救的东西，按 0 条返回
            log.warn("[CASE] 宽松档无法解包模型输出：{}", ex.getMessage());
            return out;
        }
        List<JsonNode> valid = jsonSchemaValidator.filterValidItems(array, schemaPath);
        valid.forEach(out::add);
        return out;
    }

    /**
     * 发一次调用并记账。
     *
     * <p>{@code degraded} 恒 {@code false}：{@link LlmRequest#isDegraded()} 只用于
     * {@code llm_call_log} 的"这次调用属于降级批次"标记，而返修轮次的降级性质<b>已经由
     * {@code scene=CASE_REPAIR} 表达</b>了。两处都标记会让"降级调用数"在按 scene 统计和
     * 按 degraded 统计时对不上。</p>
     *
     * @param context     上下文（提供 taskId/taskItemId/attemptNo）
     * @param prompt      渲染后的用户提示词
     * @param schemaText  schema 原文（为空则不带结构化要求）
     * @param scene       场景（{@code CASE_GENERATE} / {@code CASE_REPAIR}）
     * @param temperature 温度
     * @param ledger      指标台账
     * @return 模型响应
     */
    private LlmResponse call(GenerationContext context, String prompt, String schemaText,
                             String scene, double temperature, Ledger ledger) {
        LlmRequest request = LlmRequest.builder()
                .scene(scene)
                .taskId(context.taskId())
                .taskItemId(context.taskItemId())
                .attemptNo(context.attemptNo())
                .degraded(false)
                .message(LlmMessage.user(prompt))
                .temperature(temperature)
                .jsonSchema(schemaText == null || schemaText.isBlank() ? null : schemaText)
                .build();
        LlmResponse response = loggingLlmProvider.chat(request);
        accumulate(ledger, response);
        return response;
    }

    /**
     * 累加一次调用的 token 与成本。
     *
     * @param ledger   台账
     * @param response 响应（可为 {@code null}，此时忽略）
     */
    private void accumulate(Ledger ledger, LlmResponse response) {
        if (response == null) {
            return;
        }
        LlmUsage usage = response.getUsage() == null ? LlmUsage.EMPTY : response.getUsage();
        ledger.promptTokens += usage.promptTokens();
        ledger.completionTokens += usage.completionTokens();
        ledger.costMicro += costMicroOf(response, usage);
    }

    /**
     * 单次调用的成本（微元）。
     *
     * <p><b>响应没回显 model 时退回配置的模型名</b>：单价表按模型名索引，而 "本次用的是哪个模型"
     * 的事实来源是配置（{@code ai.model}）——真实的 provider 大多会回显，但网关二次加工后
     * 可能丢掉该字段（T07-1 的已知缺口）。用配置值兜底比记 0 更接近真相，且不会像"套一个
     * 默认模型单价"那样把成本算错（配置值就是本次请求实际用的模型）。</p>
     *
     * @param response 响应
     * @param usage    用量
     * @return 成本（微元）
     */
    private long costMicroOf(LlmResponse response, LlmUsage usage) {
        String model = response == null ? null : response.getModel();
        if (model == null || model.isBlank()) {
            model = aiProperties.getModel();
        }
        if (model == null || model.isBlank()) {
            log.debug("[CASE] 响应与配置都没有模型名，本次成本记 0");
            return 0L;
        }
        return modelPriceCache.costMicro(model, usage);
    }

    /**
     * 数组节点 → {@link CaseDraft} 列表。
     *
     * <p><b>映射失败按程序错误抛出</b>：走到这里节点已经逐个通过 schema 校验，
     * 再映射不上只有一种可能——{@link CaseDraft} 与 {@code testcase.schema.json} 字段漂移。
     * 静默吞掉会让 schema 形同虚设，还会让"模型质量差"变成背锅侠。</p>
     *
     * @param items 已通过校验的数组（可为 {@code null}）
     * @return draft 列表（可能为空）
     * @throws IllegalStateException DTO 与 schema 字段漂移
     */
    private List<CaseDraft> toDrafts(ArrayNode items) {
        if (items == null) {
            return new ArrayList<>();
        }
        List<CaseDraft> drafts = new ArrayList<>(items.size());
        for (JsonNode node : items) {
            try {
                drafts.add(objectMapper.treeToValue(node, CaseDraft.class));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException(
                        "CaseDraft 与 schema/testcase.schema.json 字段漂移，无法把模型输出映射成用例："
                                + ex.getOriginalMessage(), ex);
            }
        }
        return drafts;
    }

    /**
     * 生成模板的 5 个占位符取值。
     *
     * <p><b>一个都不能少，也不能是 null</b>：{@code PromptRenderer} 缺变量直接抛 40001，
     * {@code Map.of} 不接受 null。这里用空串表达"没有内容"，而不是省略键——
     * 省略键等于让模板渲染失败，"这个测试点没有接口上下文"是完全正常的情况。</p>
     *
     * @param context 本次生成上下文
     * @return 不可变变量表
     */
    private Map<String, Object> renderVars(GenerationContext context) {
        return Map.of(
                "test_point", nullToEmpty(context.testPointText()),
                "api_spec", nullToEmpty(context.apiSpec()),
                "case_types", nullToEmpty(context.caseTypes()),
                "priority_rules", nullToEmpty(context.priorityRules()),
                "few_shot", nullToEmpty(context.fewShot()));
    }

    /**
     * 读 schema 原文（读不到就是配置错，{@code JsonSchemaValidator} 会抛不可重试异常）。
     *
     * @param schemaPath schema 路径（可为空）
     * @return schema 原文；路径为空时返回空串
     */
    private String schemaTextOf(String schemaPath) {
        if (schemaPath == null || schemaPath.isBlank()) {
            return "";
        }
        return jsonSchemaValidator.readSchemaText(schemaPath);
    }

    /**
     * 把 HTTP 返回内容归一成字符串。
     *
     * @param response 响应（可为 {@code null}）
     * @return 非 null 内容
     */
    private static String contentOf(LlmResponse response) {
        if (response == null || response.getContent() == null) {
            return "";
        }
        return response.getContent();
    }

    /**
     * 校验错误归一化：剥掉 {@code JsonSchemaValidator} 加的中文前缀，压平空白。
     *
     * <p><b>为什么要归一化</b>：这段文本有两个用途——① 贴进返修提示词；② 判断"连续两轮的错误
     * 是否完全相同"。前者要求可读（不能是 {@code ValidationMessage} 的 {@code toString}），
     * 后者要求稳定（同一处错误两次的消息必须逐字相等）。{@code describe(...)} 已经保证了
     * "按路径排序、只取前 3 条"的确定性，这里只做前缀剥离与空白压缩。</p>
     *
     * @param message 异常消息
     * @return 归一化后的错误描述（非 null）
     */
    private static String normalizeValidationError(String message) {
        if (message == null) {
            return "";
        }
        String text = message.strip();
        if (text.startsWith(SCHEMA_ERROR_PREFIX)) {
            text = text.substring(SCHEMA_ERROR_PREFIX.length()).strip();
        }
        return text.replaceAll("\\s+", " ");
    }

    /**
     * 组结果并计时。
     *
     * @param drafts    草稿
     * @param verdict   档位判定
     * @param truncated 是否截断
     * @param rounds    已用返修轮次
     * @param ledger    指标台账
     * @param startNanos 计时起点
     * @return 结果
     */
    private GenerationResult build(List<CaseDraft> drafts, DegradePolicy.Verdict verdict,
                                   boolean truncated, int rounds, Ledger ledger, long startNanos) {
        int latencyMs = elapsedMs(startNanos);
        log.info("[CASE] 生成档位={} 用例={} 返修轮次={} 截断={} tokens={}/{} 成本={}μ 耗时={}ms 原因={}",
                verdict.level(), drafts.size(), rounds, truncated,
                ledger.promptTokens, ledger.completionTokens, ledger.costMicro, latencyMs,
                verdict.reason());
        return new GenerationResult(drafts, ledger.promptTokens, ledger.completionTokens,
                ledger.costMicro, latencyMs, verdict.level(), verdict.reason(), truncated, rounds);
    }

    /**
     * 整段墙钟耗时。
     *
     * @param startNanos 计时起点
     * @return 毫秒数（夹到 int 范围，落 {@code INT} 列）
     */
    private static int elapsedMs(long startNanos) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        return (int) Math.max(0L, Math.min(elapsed, Integer.MAX_VALUE));
    }

    /**
     * 截断到上限。
     *
     * @param text      原文
     * @param maxLength 上限
     * @return 原文或截断后的文本
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 空值转空串（{@code Map.of} 不接受 null，模板也不该看到 "null" 这个词）。
     *
     * @param value 原值
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 指标台账：一次 item 内所有调用求和。
     *
     * <p>用可变小对象而不是三个局部 int：返修循环里要跨轮累加，
     * 而 Java 的 lambda/long 传参不支持引用传递，写三个 {@code int[]} 比它更难读。</p>
     */
    private static final class Ledger {

        /** prompt token 累计。 */
        private int promptTokens;

        /** completion token 累计。 */
        private int completionTokens;

        /** 成本累计（微元）。 */
        private long costMicro;
    }
}
