package com.aittest.generation.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aittest.aicore.LlmException;
import com.aittest.aicore.LlmRequest;
import com.aittest.aicore.LlmResponse;
import com.aittest.aicore.LlmUsage;
import com.aittest.aicore.log.ModelPriceCache;
import com.aittest.aicore.prompt.PromptRenderer;
import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.aicore.provider.LoggingLlmProvider;
import com.aittest.aicore.provider.MockLlmProvider;
import com.aittest.aicore.structured.JsonRepairer;
import com.aittest.aicore.structured.JsonSchemaValidator;
import com.aittest.aicore.structured.StructuredOutputParser;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 三档降级编排的契约锁（T07 验收③：<b>三档可复现</b>）。
 *
 * <p><b>为什么用真实 {@link MockLlmProvider} 而不是 mock 掉 provider</b>：三档降级的全部意义是
 * "拿到坏输出之后还能不能救"。mock 掉 provider 就等于自己写死"这次返回坏 JSON"，
 * 那测的只是 if/else 分支，<b>测不到</b> {@code JsonRepairer} 的修复能力、schema 错误的路径格式、
 * 截断后能救回几条——而这些恰恰是线上真正会出问题的地方。</p>
 *
 * <p><b>真实件</b>：{@link MockLlmProvider}（7 种 {@code [[MOCK:xxx]]} 坏输出）、
 * {@link StructuredOutputParser}、{@link JsonSchemaValidator}（含真实的
 * {@code testcase.schema.json}）、{@link JsonRepairer}（静态纯函数）、{@link ObjectMapper}、
 * {@link AiProperties}（纯 POJO）。</p>
 *
 * <p><b>被 mock 的</b>：{@code PromptTemplateService}（模板内容由用例指定，才能把
 * {@code [[MOCK:xxx]]} 标记放进提示词）、{@code PromptRenderer}（同上：渲染结果就是标记本身，
 * 这样 {@link MockLlmProvider} 看到的 {@code allText} 完全可预测）、{@code LoggingLlmProvider}
 * （它只是记账装饰器，转调真实 mock provider）、{@code ModelPriceCache}（单价表要连库）。</p>
 *
 * <p><b>为什么渲染结果写死成一行标记</b>：{@link MockLlmProvider} 的输出条数 =
 * {@code 1 + floorMod(sha256(allText).hashCode(), 3)}。只有把 {@code allText} 钉死，
 * "3 条全合法""2 条里坏 1 条""截断后剩 2 条"这些断言才能写成精确值而不是"非空"。
 * 每个用例里那些看起来古怪的字符串（如 {@code "[[MOCK:truncated]]A"}）都是为了把条数
 * 定到某个具体值，注释里写明了它对应几条。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CaseGenerateOrchestratorTest {

    private static final Long TASK_ID = 9100L;

    private static final Long ITEM_ID = 9101L;

    private static final Long DOC_ID = 9200L;

    private static final Long TEST_POINT_ID = 9300L;

    /** 第 2 次尝试（{@code item.attempts=1} 之后），用来锁住"返修不递增 attemptNo"。 */
    private static final int ATTEMPT_NO = 2;

    private static final String SCHEMA_PATH = "classpath:schema/testcase.schema.json";

    /** 生成模板内容（渲染结果由 mock 的渲染器决定，这里只需要能被识别成"生成模板"）。 */
    private static final String GENERATE_CONTENT = "GENERATE:{{test_point}}|{{api_spec}}|"
            + "{{case_types}}|{{priority_rules}}|{{few_shot}}";

    /** 返修模板内容（三个占位符：原输出、校验错误、schema）。 */
    private static final String REPAIR_CONTENT = "REPAIR:{{original_output}}|"
            + "{{validation_error}}|{{schema}}";

    /** 模板编码（与 {@code prompts/*.v1.md} 的 front-matter 一致）。 */
    private static final String GENERATE_CODE = "case_generate";

    private static final String REPAIR_CODE = "case_repair";

    /**
     * 返修上限的期望值（{@code MAX_REPAIR_ROUNDS} 是私有的，这里复制一份常量语义）。
     *
     * <p>刻意不改成 {@code 2} 字面量：把"两轮"这件事写成有名字的常量，
     * 将来若允许把上限提到 3，改这里会立刻让 ⑦ 的断言失败，提醒回来核对用例——
     * 而不是静默地"3 次调用也照样通过"。</p>
     */
    private static final int MAX_ROUNDS_EXPECTED = 2;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private PromptRenderer promptRenderer;

    @Mock
    private LoggingLlmProvider loggingLlmProvider;

    @Mock
    private ModelPriceCache modelPriceCache;

    /** 真实依赖：schema 校验（含真实 schema 文件）。 */
    private JsonSchemaValidator jsonSchemaValidator;

    /** 真实依赖：结构化解析（修复 → 解包 → 校验）。 */
    private StructuredOutputParser structuredOutputParser;

    /** 真实依赖：mock 供应商（坏输出制造机）。 */
    private MockLlmProvider mockLlmProvider;

    /** 真实依赖：DTO 映射。 */
    private ObjectMapper objectMapper;

    /** 真实依赖：温度配置。 */
    private AiProperties aiProperties;

    private CaseGenerateOrchestrator orchestrator;

    /** 生成调用的渲染结果（决定 mock 的输出形状与条数）。 */
    private String generatePrompt;

    /** 返修调用的渲染结果（决定返修轮是"修好了"还是"又坏了"）。 */
    private String repairPrompt;

    /** 全部模型请求（按调用顺序）。 */
    private final List<LlmRequest> requests = new ArrayList<>();

    /** 全部模型响应（按调用顺序，用于毫不硬编码地断言指标累加）。 */
    private final List<LlmResponse> responses = new ArrayList<>();

    /** 生成模板的渲染变量（锁 5 个占位符）。 */
    private final List<Map<String, Object>> generateVars = new ArrayList<>();

    /** 返修模板的渲染变量（锁 3 个占位符与 8000 字截断）。 */
    private final List<Map<String, Object>> repairVars = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jsonSchemaValidator = new JsonSchemaValidator();
        structuredOutputParser = new StructuredOutputParser(jsonSchemaValidator);
        mockLlmProvider = new MockLlmProvider();
        objectMapper = new ObjectMapper();
        aiProperties = new AiProperties();
        orchestrator = new CaseGenerateOrchestrator(promptTemplateService, promptRenderer,
                loggingLlmProvider, jsonSchemaValidator, structuredOutputParser, objectMapper,
                aiProperties, modelPriceCache);

        generatePrompt = "[[MOCK:ok]]";
        repairPrompt = "[[MOCK:ok]]";

        lenient().when(promptTemplateService.activeOf(GENERATE_CODE))
                .thenReturn(template(GENERATE_CODE, GENERATE_CONTENT));
        lenient().when(promptTemplateService.activeOf(REPAIR_CODE))
                .thenReturn(template(REPAIR_CODE, REPAIR_CONTENT));

        lenient().when(promptRenderer.render(anyString(), anyMap())).thenAnswer(invocation -> {
            String content = invocation.getArgument(0);
            Map<String, Object> vars = invocation.getArgument(1);
            if (content.startsWith("REPAIR")) {
                repairVars.add(vars);
                return repairPrompt;
            }
            generateVars.add(vars);
            return generatePrompt;
        });

        lenient().when(loggingLlmProvider.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            requests.add(request);
            LlmResponse response = mockLlmProvider.chat(request);
            responses.add(response);
            return response;
        });

        // 单价表要连库：用"1 token = 2 微元"的确定口径，让指标累加可断言
        lenient().when(modelPriceCache.costMicro(anyString(), any(LlmUsage.class)))
                .thenAnswer(invocation -> {
                    LlmUsage usage = invocation.getArgument(1);
                    return usage == null ? 0L : (long) usage.totalTokens() * 2L;
                });
    }

    // ------------------------------------------------------------------ ① 正常档

    @Test
    @DisplayName("① 合法输出：NORMAL，3 条用例，未发生返修（只调 1 次模型）")
    void normalOutputKeepsNormalLevelWithoutRepair() {
        generatePrompt = "[[MOCK:ok]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(result.drafts()).hasSize(3);
        assertThat(result.repairRounds()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(result.lenient()).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getScene()).isEqualTo(TaskScene.CASE_GENERATE.code());
        assertThat(requests.get(0).getAttemptNo()).isEqualTo(ATTEMPT_NO);
        assertThat(requests.get(0).getTemperature())
                .isEqualTo(aiProperties.getTemperature().getGenerate());
        // 指标必须来自本次响应，不能是 0 或常量
        assertThat(result.promptTokens())
                .isEqualTo(responses.get(0).getUsage().promptTokens())
                .isPositive();
        assertThat(result.completionTokens())
                .isEqualTo(responses.get(0).getUsage().completionTokens())
                .isPositive();
    }

    @Test
    @DisplayName("①-补 5 个占位符必须全给：缺一个 PromptRenderer 就抛 40001")
    void generatePromptGetsAllFivePlaceholders() {
        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(generateVars).hasSize(1);
        Map<String, Object> vars = generateVars.get(0);
        assertThat(vars).containsKeys("test_point", "api_spec", "case_types", "priority_rules", "few_shot");
        assertThat(vars.get("test_point")).asString().contains("name 必填");
        assertThat(vars.get("api_spec")).asString().contains("POST /api/users");
        assertThat(vars.get("case_types")).asString().contains("BOUNDARY");
        assertThat(vars.get("priority_rules")).asString().contains("P0/P1/P2/P3");
        assertThat(vars.get("few_shot")).asString().contains("[BOUNDARY] 标题：");
    }

    // ------------------------------------------------------------------ ① 本地修复仍算正常

    @Test
    @DisplayName("② markdown 围栏：本地修复后 NORMAL，返修轮次 0（修复不改语义，不算降级）")
    void fencedOutputStaysNormalWithZeroRepairRounds() {
        // fence 模式下 mock 给 1 条合法用例，外面包 ```json 围栏
        generatePrompt = "[[MOCK:fence]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(result.drafts()).hasSize(1);
        assertThat(result.repairRounds()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(requests).hasSize(1);
        assertThat(result.degradeReason()).contains("本地语法修复");
        // 护栏（QA 的 A2）：可本地修好的坏输出绝不允许去问模型——台账里不许出现 CASE_REPAIR 行
        assertThat(requests).noneMatch(request -> "CASE_REPAIR".equals(request.getScene()));
    }

    @Test
    @DisplayName("③ 尾逗号：本地修复后 NORMAL，返修轮次 0")
    void trailingCommaOutputStaysNormalWithZeroRepairRounds() {
        // trailing-comma 模式下 mock 给 2 条合法用例，最后多一个逗号
        generatePrompt = "[[MOCK:trailing-comma]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.NORMAL);
        assertThat(result.drafts()).hasSize(2);
        assertThat(result.repairRounds()).isZero();
        assertThat(requests).hasSize(1);
        assertThat(requests).noneMatch(request -> "CASE_REPAIR".equals(request.getScene()));
    }

    // ------------------------------------------------------------------ ③ 截断 → 宽松档

    @Test
    @DisplayName("④ 输出被截断：LENIENT + truncated=true，且不发返修请求（只花 1 次钱）")
    void truncatedOutputFallsToLenientWithTruncatedFlag() {
        // **这个字符串是挑过的**：mock 的条数 = 1 + floorMod(sha256(allText).hashCode(), 3)，
        // "[[MOCK:truncated]]" 恰好落 count=3 的槽位——截到 3/4 处时能保住前 2 个完整元素。
        // 若改成别的字符，条数可能变成 1（唯一对象被截在中间、一个都补不全），
        // 那样本次会落到"返修/失败"而不是 LENIENT。见类注释与 QA 的 count=1 边界说明。
        generatePrompt = "[[MOCK:truncated]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.LENIENT);
        assertThat(result.truncated()).isTrue();
        assertThat(result.drafts()).hasSize(2);
        assertThat(result.repairRounds()).isZero();
        // 截断是确定性可本地修好的，不该再花一次调用（见 CaseGenerateOrchestrator 类注释）
        assertThat(requests).hasSize(1);
        assertThat(result.degradeReason()).contains("截断");
    }

    // ------------------------------------------------------------------ ② 返修档

    @Test
    @DisplayName("⑤ schema 不合规 + 返修一轮修好：REPAIRED，repairRounds=1，台账 2 次调用")
    void schemaInvalidIsRepairedInOneRound() {
        // schema-invalid 模式下 mock 给 2 条、最后一条 priority 写成"高"
        generatePrompt = "[[MOCK:schema-invalid]]";
        repairPrompt = "[[MOCK:ok]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.REPAIRED);
        assertThat(result.repairRounds()).isEqualTo(1);
        assertThat(result.drafts()).hasSize(3);
        // 主调用 + 1 轮返修 = 2 次；返修必须记 CASE_REPAIR 场景，degraded 保持 false（分成本用 scene）
        assertThat(requests).hasSize(2);
        LlmRequest mainRequest = requests.get(0);
        LlmRequest repairRequest = requests.get(1);
        assertThat(repairRequest.getScene()).isEqualTo("CASE_REPAIR");
        assertThat(repairRequest.isDegraded()).isFalse();
        // 台账三元组必须透传：漏 taskItemId → 按 item 查修复记录会漏行（假阴性）；
        // 漏 taskId → llm_call_log 里会积 task_id IS NULL 的行（T06 已踩过一次）
        assertThat(repairRequest.getTaskId()).isEqualTo(TASK_ID);
        assertThat(repairRequest.getTaskItemId()).isEqualTo(ITEM_ID);
        // 返修的 attemptNo 与生成行**同一值**（返修是同一尝试内的补救，不是新一次尝试）
        assertThat(repairRequest.getAttemptNo()).isEqualTo(ATTEMPT_NO);
        assertThat(repairRequest.getAttemptNo()).isEqualTo(mainRequest.getAttemptNo());
        // 温度与主调用同值（不降到 0）
        assertThat(repairRequest.getTemperature())
                .isEqualTo(aiProperties.getTemperature().getGenerate());
        // 返修也必须带 jsonSchema（不带等于让模型盲修）
        assertThat(repairRequest.getJsonSchema()).isEqualTo(mainRequest.getJsonSchema());
        assertThat(repairRequest.getJsonSchema()).contains("minItems");
        // 返修提示词的三个占位符
        assertThat(repairVars).hasSize(1);
        Map<String, Object> vars = repairVars.get(0);
        assertThat(vars).containsKeys("original_output", "validation_error", "schema");
        assertThat(vars.get("schema").toString()).contains("minItems");
        assertThat(vars.get("validation_error").toString()).contains("priority");
        // 归一化后的错误里不该出现 networknt 的 "$" 实例路径前缀堆叠
        assertThat(vars.get("validation_error").toString()).doesNotStartWith("模型输出未通过结构校验");
    }

    @Test
    @DisplayName("⑥ 返修轮的错误与上一轮完全相同：短路提前结束，LENIENT 且保住合规元素")
    void identicalRepairErrorShortCircuitsToLenient() {
        // 主调用与返修都是 schema-invalid 且 allText 相同 → 错误路径逐字一致 → 触发短路
        generatePrompt = "[[MOCK:schema-invalid]]";
        repairPrompt = "[[MOCK:schema-invalid]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.LENIENT);
        // 只跑了 1 轮返修（第 2 轮的错误必然与第 1 轮相同，再问一遍是白烧钱）
        assertThat(result.repairRounds()).isEqualTo(1);
        assertThat(requests).hasSize(2);
        // 2 条里只有最后一条 priority 非法 → 保住 1 条
        assertThat(result.drafts()).hasSize(1);
        assertThat(result.truncated()).isFalse();
        assertThat(result.degradeReason()).contains("未经完整结构校验");
    }

    @Test
    @DisplayName("⑦ 返修两轮都用尽且全不合规：LENIENT + drafts 为空（由 handler 判 E_SCHEMA_INVALID）")
    void repairExhaustedWithoutAnyValidItemReturnsEmptyDrafts() {
        // 主调用：2 条里坏 1 条（错误路径 [1].priority）
        generatePrompt = "[[MOCK:schema-invalid]]";
        // 返修：条数为 1（该字符串的摘要决定）→ 唯一那条非法 → 一条都救不回来；
        // 且错误路径 [0].priority ≠ 主调用的 [1].priority，所以不会短路，两轮都会真跑
        repairPrompt = "[[MOCK:schema-invalid]]D";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.LENIENT);
        assertThat(result.repairRounds()).isEqualTo(MAX_ROUNDS_EXPECTED);
        assertThat(result.drafts()).isEmpty();
        assertThat(result.produced()).isZero();
        assertThat(requests).hasSize(MAX_ROUNDS_EXPECTED + 1);
        assertThat(result.degradeReason()).contains("没有可保留");
    }

    // ------------------------------------------------------------------ 上游失败

    @Test
    @DisplayName("⑧ 上游 5xx：LlmException 冒泡，不在这里吞掉（退避重试是 worker 的职责）")
    void upstreamErrorPropagates() {
        generatePrompt = "[[MOCK:upstream-error]]";

        assertThatThrownBy(() -> orchestrator.generate(context()))
                .isInstanceOf(LlmException.class)
                .satisfies(ex -> assertThat(((LlmException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_LLM_UPSTREAM))
                .satisfies(ex -> assertThat(((LlmException) ex).isRetryable()).isTrue());
    }

    // ------------------------------------------------------------------ 指标累加

    @Test
    @DisplayName("⑨ 指标是本次 item 全部调用之和：主调用 + 返修，token/成本逐笔累加")
    void metricsAccumulateAcrossRepairRounds() {
        generatePrompt = "[[MOCK:schema-invalid]]";
        repairPrompt = "[[MOCK:ok]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(requests).hasSize(2);
        int expectedPrompt = 0;
        int expectedCompletion = 0;
        long expectedCost = 0L;
        for (LlmResponse response : responses) {
            expectedPrompt += response.getUsage().promptTokens();
            expectedCompletion += response.getUsage().completionTokens();
            expectedCost += (long) response.getUsage().totalTokens() * 2L;
        }
        assertThat(result.promptTokens()).isEqualTo(expectedPrompt);
        assertThat(result.completionTokens()).isEqualTo(expectedCompletion);
        assertThat(result.costMicro()).isEqualTo(expectedCost);
        // 只取主调用会明显偏小——这正是不许"随手拿第一次响应的 usage"的原因
        assertThat(result.promptTokens())
                .isGreaterThan(responses.get(0).getUsage().promptTokens());
        // 耗时是整段墙钟，不是某一次调用
        assertThat(result.latencyMs()).isNotNegative();
    }

    @Test
    @DisplayName("⑩ 返修提示词的 {{original_output}} 截断到 8000 字（防止跑飞的输出撑爆上下文）")
    void repairOriginalOutputIsCappedAtEightThousandChars() {
        String json = "{\"cases\":[{\"priority\":\"高\"}]}";
        String padded = " ".repeat(9000 - json.length()) + json;
        LlmResponse huge = LlmResponse.builder()
                .content(padded)
                .usage(new LlmUsage(300, 50))
                .latencyMs(7L)
                .model("mock-model")
                .build();
        when(loggingLlmProvider.chat(any(LlmRequest.class))).thenAnswer(invocation -> {
            LlmRequest request = invocation.getArgument(0);
            requests.add(request);
            LlmResponse response = requests.size() == 1
                    ? huge
                    : mockLlmProvider.chat(request);
            responses.add(response);
            return response;
        });
        repairPrompt = "[[MOCK:ok]]";

        GenerationResult result = orchestrator.generate(context());

        assertThat(result.level()).isEqualTo(DegradeLevel.REPAIRED);
        assertThat(repairVars).hasSize(1);
        String original = String.valueOf(repairVars.get(0).get("original_output"));
        assertThat(original).hasSize(8000);
    }

    // ------------------------------------------------------------------ 夹具

    private static GenerationContext context() {
        return new GenerationContext(
                TASK_ID,
                ITEM_ID,
                DOC_ID,
                TEST_POINT_ID,
                ATTEMPT_NO,
                "case_generate.v1",
                "标题：创建用户时 name 必填\n分类：FUNCTIONAL",
                "POST /api/users\n创建用户",
                "FUNCTIONAL/BOUNDARY/EXCEPTION/SECURITY/PERFORMANCE/COMPATIBILITY",
                "P0/P1/P2/P3，按影响面与发生概率评定",
                "- [BOUNDARY] 标题：name 为 64 个字符时创建成功；预期：返回 HTTP 200 且 code=0");
    }

    private static PromptTemplate template(String code, String content) {
        PromptTemplate template = new PromptTemplate();
        template.setCode(code);
        template.setVersion(1);
        template.setContent(content);
        template.setOutputSchema(SCHEMA_PATH);
        return template;
    }
}
