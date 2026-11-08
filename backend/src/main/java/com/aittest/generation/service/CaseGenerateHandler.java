package com.aittest.generation.service;

import com.aittest.aicore.LlmMessage;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.aicore.log.ModelPriceCache;
import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.CaseDraft;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.aittest.common.ErrorCode;
import com.aittest.common.JsonUtils;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.doc.domain.ApiOperation;
import com.aittest.doc.domain.DocSection;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用例生成场景的 item 执行器：**一个测试点 → 1~3 条用例落库**。
 *
 * <p><b>与抽点场景的分工</b>：引擎只知道"把 N 个 item 跑完"，本类只负责"这一个测试点展开成用例"。
 * 状态机、调度、退避、进度推送全在 {@code GenTaskWorker} / {@code GenTaskService}，
 * 本类<b>不重写其中任何一环</b>，也不碰 {@code onTaskFinalize}（用例生成没有文档级收尾动作）。</p>
 *
 * <p><b>失败一律收成 {@link ItemOutcome#failed}</b>（D-27 的失败隔离纪律）：
 * "没关联测试点""测试点被别人删了""模型一条都没给"都是<b>预期内</b>的结果，
 * 抛异常只能表达"第一条失败"。真正的程序错误（NPE、schema 与 DTO 漂移）照旧抛出，
 * 由 worker 按退避重试 3 次后置 FAILED。</p>
 *
 * <p><b>指标必须真回写</b>（T07 验收②）：{@code promptTokens / completionTokens /
 * costMicro / latencyMs} 全部取自本次 {@link LlmResponse}。抽点侧这四个值恒传 0
 * 是历史遗留，<b>不要照抄</b>——那样"每个任务的成本、每个 item 的耗时"永远是 0，
 * 后面所有成本报表都是空的。</p>
 *
 * <p><b>已知边界（留 T07-2）</b>：</p>
 * <ol>
 *   <li><b>降级解析没有接进来</b>。{@code parsed.truncated()} 为真时按 D-03 本应
 *       {@code degraded=1} 且 {@code confidence=0.500}，但"三档降级编排"是 T07-2 的范围，
 *       本批只<b>打日志</b>不改语义。所以当前 {@code confidence} 恒 {@code 1.000}——
 *       这是已知偏差，不是漏写。</li>
 *   <li><b>few-shot 传空串</b>：素材库（按文档/分类挑历史已采纳用例）属于 T07-2。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Service
public class CaseGenerateHandler implements TaskItemHandler {

    private static final Logger log = LoggerFactory.getLogger(CaseGenerateHandler.class);

    /** 提示词模板编码（{@code prompts/case_generate.v1.md} 的 front-matter {@code code}）。 */
    private static final String TEMPLATE_CODE = "case_generate";

    /** {@code gen_task_item.unit_ref} 里测试点定位的前缀：{@code testpoint:<id>}。 */
    private static final String UNIT_REF_PREFIX = "testpoint:";

    /** 章节正文喂给模型的上限（章节可能几万字，全塞进去会撑爆上下文）。 */
    private static final int SECTION_CONTENT_MAX = 2000;

    /** {@code test_case.title} 是 {@code VARCHAR(500)}，兜底截断长度（schema 已限 200）。 */
    private static final int TITLE_MAX = 500;

    /** 优先级默认值（{@code test_case.priority} 有 DEFAULT 'P2'，这里与之对齐）。 */
    private static final String DEFAULT_PRIORITY = "P2";

    /** 优先级判定规则（提示词占位符 {@code {{priority_rules}}} 的取值）。 */
    private static final String PRIORITY_RULES = "P0/P1/P2/P3，按影响面与发生概率评定";

    /**
     * 兜底置信度。
     *
     * <p><b>为什么是常量而不是"模型给的值"</b>：{@code schema/testcase.schema.json} 里
     * 没有 {@code confidence} 字段且 {@code additionalProperties: false}，
     * 模型<b>写不出</b>置信度（写了会被 schema 拒掉）。所以这里只能由服务端定：
     * 正常解析 1.000，降级解析 0.500（降级分支留 T07-2，见类注释）。</p>
     */
    private static final BigDecimal CONFIDENCE_NORMAL = new BigDecimal("1.000");

    /** 测试点定位（读 {@code test_point}）。 */
    private final TestPointMapper testPointMapper;

    /** 接口上下文（{@code test_point.operation_id} → method/path/summary）。 */
    private final ApiOperationMapper apiOperationMapper;

    /** 章节上下文（{@code test_point.section_id} → 标题 + 正文摘要）。 */
    private final DocSectionMapper docSectionMapper;

    /** 用例落库（幂等写入）。 */
    private final TestCaseMapper testCaseMapper;

    /** 模板查询。 */
    private final PromptTemplateService promptTemplateService;

    /** 提示词渲染。 */
    private final PromptRenderer promptRenderer;

    /** 记账装饰器（必须走它，否则生成不进成本台账）。 */
    private final LoggingLlmProvider loggingLlmProvider;

    /** schema 读取与校验。 */
    private final JsonSchemaValidator jsonSchemaValidator;

    /** 结构化解析（修复 → 解包 → 校验）。 */
    private final StructuredOutputParser structuredOutputParser;

    /** JSON 转换（把校验通过的节点映射成 {@link CaseDraft}，以及把 steps 拼成 JSON 数组）。 */
    private final ObjectMapper objectMapper;

    /** 温度配置（生成 0.4）。 */
    private final AiProperties aiProperties;

    /** 成本换算（token → 微元）。 */
    private final ModelPriceCache modelPriceCache;

    public CaseGenerateHandler(TestPointMapper testPointMapper,
                               ApiOperationMapper apiOperationMapper,
                               DocSectionMapper docSectionMapper,
                               TestCaseMapper testCaseMapper,
                               PromptTemplateService promptTemplateService,
                               PromptRenderer promptRenderer,
                               LoggingLlmProvider loggingLlmProvider,
                               JsonSchemaValidator jsonSchemaValidator,
                               StructuredOutputParser structuredOutputParser,
                               ObjectMapper objectMapper,
                               AiProperties aiProperties,
                               ModelPriceCache modelPriceCache) {
        this.testPointMapper = testPointMapper;
        this.apiOperationMapper = apiOperationMapper;
        this.docSectionMapper = docSectionMapper;
        this.testCaseMapper = testCaseMapper;
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
     * 本处理器负责的场景。
     *
     * @return {@link TaskScene#CASE_GENERATE}
     */
    @Override
    public TaskScene scene() {
        return TaskScene.CASE_GENERATE;
    }

    /**
     * 把一个测试点展开成 1~3 条用例并落库。
     *
     * <p><b>执行顺序</b>：定位测试点 → 取模板与版本 → 渲染（5 个占位符必须全给，
     * 缺一个 {@code PromptRenderer} 会抛 40001）→ 调模型（带任务上下文与真实 attemptNo）
     * → 解析 + schema 校验 → 幂等落库（先 upsert 再删多余序号）→ 回写指标。</p>
     *
     * @param task 所属任务（提供 docId、taskId、model 兜底）
     * @param item 待执行的子项（提供 taskItemId、attempts、测试点定位）
     * @return 产出条数与真实指标；预期内失败返回 {@link ItemOutcome#failed}
     */
    @Override
    public ItemOutcome execute(GenTask task, GenTaskItem item) {
        // 1) 定位测试点：先看外键，再退回 unit_ref 里的 testpoint:<id>
        Long testPointId = resolveTestPointId(item);
        if (testPointId == null) {
            return ItemOutcome.failed("E_PARAM_INVALID", "任务子项未关联测试点", 0);
        }
        TestPoint testPoint = testPointMapper.selectById(testPointId);
        if (testPoint == null) {
            // 测试点被物理删了（外键是 SET NULL，历史 item 仍指向旧 id）
            return ItemOutcome.failed("E_NOT_FOUND", "测试点不存在：" + testPointId, 0);
        }

        // 2) 模板与版本。版本号必须在这里记下：模板改版后老用例的这个字段不能跟着变
        PromptTemplate template = promptTemplateService.activeOf(TEMPLATE_CODE);
        String schemaPath = template.getOutputSchema();
        String promptVersion = template.getCode() + ".v" + template.getVersion();

        // 3) 渲染。5 个占位符一个都不能缺（缺了 PromptRenderer 直接 40001），
        //    没有内容的一律传空串而不是 null（Map.of 也不接受 null）。
        String rendered = promptRenderer.render(template.getContent(), Map.of(
                "test_point", testPointTextOf(testPoint),
                "api_spec", apiSpecOf(testPoint),
                "case_types", allowedCaseTypes(),
                "priority_rules", PRIORITY_RULES,
                "few_shot", ""));

        // 4) 调模型。attemptNo 跟着 item 的重试次数走：llm_call_log 靠它算首次通过率，
        //    恒传 1 会把"重试 3 次才成功"记成"一次就成功"（抽点侧的已知偏差，不要复制）。
        LlmRequest request = LlmRequest.builder()
                .scene(TaskScene.CASE_GENERATE.code())
                .taskId(task.getId())
                .taskItemId(item.getId())
                .attemptNo(attemptNoOf(item))
                .message(LlmMessage.user(rendered))
                .temperature(aiProperties.getTemperature().getGenerate())
                .jsonSchema(schemaPath == null || schemaPath.isBlank()
                        ? null
                        : jsonSchemaValidator.readSchemaText(schemaPath))
                .build();

        LlmResponse response = loggingLlmProvider.chat(request);
        int latencyMs = latencyMsOf(response);

        // 5) 解析 + schema 校验。LlmException 不在这里接：模型/上游类失败应当让 worker
        //    按退避重试（3 次后置 FAILED），而不是"一次失败就定终身"。
        StructuredOutputParser.ParseResult parsed =
                structuredOutputParser.parseArrayWithMeta(response.getContent(), schemaPath);
        List<CaseDraft> drafts = toDrafts(parsed.items());

        if (parsed.truncated()) {
            // D-03：截断补全属于降级解析，本应 degraded=1 + confidence=0.500。
            // 三档降级编排留 T07-2，这里只留痕，便于事后按日志找出"疑似低质批"。
            log.warn("[CASE] 模型输出被截断并补全，按 NORMAL 记置信度（降级编排待 T07-2）itemId={}",
                    item.getId());
        }

        // 6) 一条都没有：不算成功。返回 0 会让任务看起来"跑完了但没产出"，
        //    而真实原因是模型没给东西，必须显式失败。
        //    错误码取枚举 name() 而不是字符串字面量：改名时跟着走，不会出现"枚举叫 A、库里存 B"。
        if (drafts.isEmpty()) {
            log.warn("[CASE] 模型未产出任何用例 itemId={} testPointId={}", item.getId(), testPointId);
            return ItemOutcome.failed(ErrorCode.E_LLM_EMPTY.name(), "模型未产出任何用例", latencyMs);
        }

        // 7) 幂等落库：先 upsert 覆盖，再删本次 item 的多余序号
        String model = resolveModel(task, response);
        List<TestCase> rows = toRows(task, item, testPoint, drafts, promptVersion, model);
        testCaseMapper.upsertBatch(rows);
        int trimmed = testCaseMapper.deleteStaleSeqNo(item.getId(), rows.size());

        // 8) 指标真回写（不要像抽点侧那样全传 0）
        LlmUsage usage = response.getUsage() == null ? LlmUsage.EMPTY : response.getUsage();
        int promptTokens = usage.promptTokens();
        int completionTokens = usage.completionTokens();
        long costMicro = costMicroOf(response, usage);

        log.info("[CASE] 生成完成 taskNo={} itemId={} 测试点={} 用例={} 清理残留={} 版本={} 模型={} "
                        + "tokens={}/{} 成本={}μ 耗时={}ms",
                task.getTaskNo(), item.getId(), testPointId, rows.size(), trimmed,
                promptVersion, model, promptTokens, completionTokens, costMicro, latencyMs);
        return ItemOutcome.ok(rows.size(), promptTokens, completionTokens, costMicro, latencyMs);
    }

    /**
     * 解析出的 JSON 数组 → {@link CaseDraft} 列表。
     *
     * <p><b>映射失败按程序错误抛出，不当成"这次没生成"</b>：走到这里节点已经通过
     * {@code schema/testcase.schema.json} 校验，再映射不上只有一种可能——
     * {@link CaseDraft} 与 schema 字段漂移了（改了其中一个没改另一个）。
     * 静默吞掉会让 schema 形同虚设，还会让"模型质量差"变成背锅侠。</p>
     *
     * @param items 已通过 schema 校验的数组
     * @return draft 列表（与 items 等长，可能为空数组）
     * @throws IllegalStateException {@link CaseDraft} 与 schema 字段漂移
     */
    private List<CaseDraft> toDrafts(ArrayNode items) {
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
     * draft 列表 → 待写入行（{@code seq_no} 从 1 递增，状态固定 {@code DRAFT}）。
     *
     * @param task          所属任务
     * @param item          本次子项
     * @param testPoint     关联测试点
     * @param drafts        模型产出
     * @param promptVersion 本次使用的模板版本
     * @param model         本次使用的模型
     * @return 待写入行
     */
    private List<TestCase> toRows(GenTask task, GenTaskItem item, TestPoint testPoint,
                                  List<CaseDraft> drafts, String promptVersion, String model) {
        List<TestCase> rows = new ArrayList<>(drafts.size());
        int seqNo = 1;
        for (CaseDraft draft : drafts) {
            String stepsJson = stepsJsonOf(draft.steps());
            TestCase row = new TestCase();
            // 绕开 BaseMapper.insert 就走不到 @TableId 的主键填充，id 必须自己生成
            row.setId(IdWorker.getId());
            row.setTaskId(task.getId());
            row.setTaskItemId(item.getId());
            row.setTestPointId(testPoint.getId());
            row.setDocId(task.getDocId());
            row.setSeqNo(seqNo++);
            row.setTitle(truncate(nullToEmpty(draft.title()), TITLE_MAX));
            row.setPrecondition(nullToEmpty(draft.precondition()));
            row.setSteps(stepsJson);
            row.setExpected(nullToEmpty(draft.expected()));
            row.setPriority(isBlank(draft.priority()) ? DEFAULT_PRIORITY : draft.priority());
            row.setCaseType(isBlank(draft.caseType()) ? CaseType.FUNCTIONAL.code() : draft.caseType());
            // 新生成的一律 DRAFT；重跑时 upsert 不覆盖 status，评审结果不会被冲掉
            row.setStatus(CaseStatus.DRAFT.code());
            row.setConfidence(CONFIDENCE_NORMAL);
            row.setPromptVersion(promptVersion);
            row.setModel(model);
            row.setContentHash(contentHashOf(row.getTitle(), stepsJson, row.getExpected()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 把模型的 {@code string[]} 步骤拼成 DDL 注释里的 JSON **数组**字符串：
     * {@code [{"no":1,"action":"...","data":""}]}。
     *
     * <p><b>为什么要转成对象数组</b>：列注释与前端契约都按
     * {@code {no, action, data}} 结构写（{@code no} 给"失败在第几步"用，
     * {@code data} 留给请求体/入参）。模型的 schema 只给 {@code string[]}
     * （一步一个动作、不要求它编造请求体），所以 {@code data} 恒空串——
     * 这是<b>有意的有损映射</b>，不是漏填：让模型编请求体只会得到幻觉数据。</p>
     *
     * @param steps 模型给的步骤（可为 null，等价于空数组）
     * @return JSON 数组字符串
     */
    private String stepsJsonOf(List<String> steps) {
        ArrayNode array = objectMapper.createArrayNode();
        if (steps != null) {
            int no = 1;
            for (String step : steps) {
                ObjectNode node = array.addObject();
                node.put("no", no++);
                node.put("action", nullToEmpty(step));
                node.put("data", "");
            }
        }
        return JsonUtils.toJson(array);
    }

    /**
     * 测试点的可读文本（提示词 {@code {{test_point}}}）。
     *
     * @param point 测试点
     * @return 多行文本（字段缺失的行直接省略，不写"null"）
     */
    private String testPointTextOf(TestPoint point) {
        StringBuilder out = new StringBuilder();
        appendLine(out, "标题", point.getTitle());
        appendLine(out, "分类", point.getCategory());
        appendLine(out, "风险等级", point.getRiskLevel());
        appendLine(out, "来源", point.getSourceRef());
        appendLine(out, "描述", point.getDescription());
        return out.toString().trim();
    }

    /**
     * 接口/需求上下文（提示词 {@code {{api_spec}}}）。
     *
     * <p><b>拿不到就传空串，绝不编造</b>：提示词里明确要求"判断字段名、错误码、边界值时
     * 只能依据这里"，凭空补一段"常见的 POST /api/users"会让模型生成一堆对不上真实接口的用例——
     * 那比空着更糟，因为空着时模型会写"返回业务错误码"，而编造的上下文会让它写死一个不存在的码。</p>
     *
     * @param point 测试点（携带 {@code operationId} 或 {@code sectionId}）
     * @return {@code METHOD /path\nsummary} 或 {@code 标题\n正文摘要}；都没有则空串
     */
    private String apiSpecOf(TestPoint point) {
        if (point.getOperationId() != null) {
            ApiOperation operation = apiOperationMapper.selectById(point.getOperationId());
            if (operation != null) {
                StringBuilder out = new StringBuilder();
                out.append(nullToEmpty(operation.getMethod()))
                        .append(' ')
                        .append(nullToEmpty(operation.getPath()));
                if (!isBlank(operation.getSummary())) {
                    out.append('\n').append(operation.getSummary());
                }
                return out.toString().trim();
            }
        }
        if (point.getSectionId() != null) {
            DocSection section = docSectionMapper.selectById(point.getSectionId());
            if (section != null) {
                return (nullToEmpty(section.getHeading()) + "\n"
                        + abbreviate(nullToEmpty(section.getContent()))).trim();
            }
        }
        return "";
    }

    /**
     * 可用用例类型（提示词 {@code {{case_types}}}）。
     *
     * <p>从 {@link CaseType} 枚举<b>现算</b>，不写死一份：枚举是分类的单一真源（D-14），
     * 写死就等于又造一个真源，两边漂移时模型会被引导去写 schema 拒收的值。</p>
     *
     * @return 以 {@code /} 连接的分类编码
     */
    private String allowedCaseTypes() {
        StringBuilder out = new StringBuilder();
        for (CaseType type : CaseType.values()) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(type.code());
        }
        return out.toString();
    }

    /**
     * 测试点定位：外键优先，其次 {@code unit_ref} 的 {@code testpoint:<id>}。
     *
     * @param item 子项
     * @return 测试点 ID；两处都拿不到返回 {@code null}
     */
    private Long resolveTestPointId(GenTaskItem item) {
        if (item.getTestPointId() != null) {
            return item.getTestPointId();
        }
        String unitRef = item.getUnitRef();
        if (unitRef == null || !unitRef.startsWith(UNIT_REF_PREFIX)) {
            return null;
        }
        String raw = unitRef.substring(UNIT_REF_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            // 脏 unit_ref（例如 testpoint:abc）：当成"没关联"处理，不抛异常
            log.warn("[CASE] unit_ref 里的测试点 id 不是数字，按未关联处理 unitRef={}", unitRef);
            return null;
        }
    }

    /**
     * 本次调用的 attemptNo（从 1 开始）。
     *
     * <p>{@code item.attempts} 记的是<b>已经失败过几次</b>，所以本次是第 {@code attempts + 1} 次。
     * 它落到 {@code llm_call_log.attempt_no}，是"首次通过率 / 平均重试次数"两个指标的唯一来源。</p>
     *
     * @param item 子项
     * @return {@code attempts + 1}
     */
    private int attemptNoOf(GenTaskItem item) {
        return (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
    }

    /**
     * 本次使用的模型名：响应自报优先，其次任务配置，最后全局配置。
     *
     * <p>落库用它是为了"哪个模型的用例被驳回最多"这类分析有据可查。</p>
     *
     * @param task     所属任务（带本次提交的 model）
     * @param response 模型响应（mock 与部分网关会回显 model）
     * @return 非空模型名
     */
    private String resolveModel(GenTask task, LlmResponse response) {
        if (response != null && !isBlank(response.getModel())) {
            return response.getModel();
        }
        if (task != null && !isBlank(task.getModel())) {
            return task.getModel();
        }
        return nullToEmpty(aiProperties.getModel());
    }

    /**
     * 成本（微元）。
     *
     * <p><b>响应没回显 model 时成本记 0</b>：单价表按模型名索引，拿不到模型名就算不出钱，
     * 硬套一个默认模型的单价会把成本算错（比记 0 更糟——0 一眼能看出是缺数据）。
     * 这类调用事后可以用 {@code llm_call_log} 里的 token 数按任务配置的模型回填，留 T07-2。</p>
     *
     * @param response 模型响应
     * @param usage    token 用量
     * @return 成本（微元）
     */
    private long costMicroOf(LlmResponse response, LlmUsage usage) {
        String model = response == null ? null : response.getModel();
        if (isBlank(model)) {
            log.debug("[CASE] 响应未回显 model，本次成本记 0（待 T07-2 用 llm_call_log 回填）");
            return 0L;
        }
        return modelPriceCache.costMicro(model, usage);
    }

    /**
     * 耗时（毫秒，夹到 int 范围内以便落 {@code INT} 列）。
     *
     * @param response 模型响应
     * @return 非负毫秒数
     */
    private int latencyMsOf(LlmResponse response) {
        long latency = response == null ? 0L : response.getLatencyMs();
        return (int) Math.max(0L, Math.min(latency, Integer.MAX_VALUE));
    }

    /**
     * 内容哈希：{@code sha256(title \n steps \n expected)}。
     *
     * <p><b>为什么不带 {@code status}/{@code confidence}/{@code promptVersion}</b>：
     * 那三个会随评审与模板改版变化，掺进哈希后"同一份内容"会被算成不同内容，
     * 查重（{@code idx_test_case_content_hash}）就失效了。
     * 分隔符用 {@code \n}：三个字段都是人写文本，{@code \n} 是它们天然的分隔，
     * 且碰撞边界（{@code "a\nb"} vs {@code "a"+"\nb"}）在标题里会被 schema 的长度与
     * 内容约束挡住——这里不需要 {@code TestPointHasher} 那种 {@code \0} 的强度。</p>
     *
     * <p><b>关于又写了一份 SHA-256</b>：{@code TestPointHasher} / {@code DocService} /
     * {@code TokenService} / {@code MockLlmProvider} 各有各的截断与编码口径，
     * 合并属于独立清理任务，不在本文件顺手做（与 {@code TestPointHasher} 类注释同一立场）。</p>
     *
     * @param title     标题（落库值）
     * @param stepsJson 步骤 JSON（落库值）
     * @param expected  预期（落库值）
     * @return 64 位小写十六进制
     */
    private static String contentHashOf(String title, String stepsJson, String expected) {
        String material = title + "\n" + stepsJson + "\n" + expected;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JDK 一定带 SHA-256；走到这里说明运行环境被动过手脚，不能吞
            throw new IllegalStateException("运行环境缺少 SHA-256，无法计算用例去重键", ex);
        }
    }

    /**
     * 追加一行"标签：值"（值为空白时整行省略，避免提示词里出现 {@code 描述：null}）。
     *
     * @param out   输出缓冲
     * @param label 标签
     * @param value 值
     */
    private static void appendLine(StringBuilder out, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        out.append(label).append('：').append(value.trim()).append('\n');
    }

    /**
     * 截断到上限（{@code test_case.title} 是 VARCHAR，超长会 1406）。
     *
     * @param text      原文
     * @param maxLength 上限
     * @return 截断后的文本
     */
    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /**
     * 截断章节正文（只用于喂模型，不落库）。
     *
     * @param content 正文
     * @return 不超过 {@link #SECTION_CONTENT_MAX} 的文本
     */
    private static String abbreviate(String content) {
        return content.length() <= SECTION_CONTENT_MAX
                ? content
                : content.substring(0, SECTION_CONTENT_MAX) + "…";
    }

    /**
     * 空白判断（null / 空串 / 全空白）。
     *
     * @param value 原值
     * @return 是否空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 空值转空串（提示词变量不接受 null；{@code Map.of} 也不接受）。
     *
     * @param value 原值
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
