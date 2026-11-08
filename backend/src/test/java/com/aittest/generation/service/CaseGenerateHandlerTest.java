package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code CASE_GENERATE} 场景的执行契约锁（T07 首次跑通：测试点 → 用例落库）。
 *
 * <p><b>本类守的六条纪律</b>（每条都对应一次真实事故或一个不可回退的契约）：</p>
 * <ol>
 *   <li><b>指标真回写</b>：token / 成本 / 耗时取本次 {@code LlmResponse}，不是全 0
 *       （抽点侧恒传 0 的历史遗留不能复制）；</li>
 *   <li><b>预期内失败不抛异常</b>（D-27）：没关联测试点、测试点被删、模型空产出，
 *       一律 {@link ItemOutcome#failed}，否则批量执行只能表达"第一条失败"；</li>
 *   <li><b>空产出不算成功</b>：返回 {@code produced=0} 会让任务看起来"跑完了"，
 *       真实原因是模型没给东西，必须显式失败；</li>
 *   <li><b>{@code attemptNo} 跟 item.attempts 走</b>：它是"首次通过率"的唯一来源，
 *       恒传 1 会把"重试 3 次才成功"记成"一次就成功"；</li>
 *   <li><b>重跑要清尾部残留</b>：上次 3 条、这次 2 条时删掉 {@code seq_no > 2}，
 *       否则评审页会多出一条本轮根本没生成的用例；</li>
 *   <li><b>新生成一律 DRAFT</b>，且 {@code status} 不在 upsert 的更新列里
 *       （重新生成不能把 ACCEPTED 打回 DRAFT）。</li>
 * </ol>
 *
 * <p><b>纯 Mockito，不连库</b>：本类不使用任何 lambda wrapper（落库语句全在
 * {@code TestCaseMapper.xml}），因此不需要装 {@code MybatisPlusLambdaCache}；
 * 实体元数据只在 {@code IdWorker} 与 MP 内部用到，本链路不触达。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CaseGenerateHandlerTest {

    private static final Long TASK_ID = 8100L;

    private static final Long ITEM_ID = 8101L;

    private static final Long TEST_POINT_ID = 7001L;

    private static final Long DOC_ID = 9001L;

    private static final String TASK_NO = "T20261108-000100";

    private static final String TEMPLATE_CODE = "case_generate";

    private static final String SCHEMA_PATH = "classpath:schema/testcase.schema.json";

    private static final String MODEL = "mock-model";

    @Mock
    private TestPointMapper testPointMapper;

    @Mock
    private ApiOperationMapper apiOperationMapper;

    @Mock
    private DocSectionMapper docSectionMapper;

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private PromptRenderer promptRenderer;

    @Mock
    private LoggingLlmProvider loggingLlmProvider;

    @Mock
    private JsonSchemaValidator jsonSchemaValidator;

    @Mock
    private StructuredOutputParser structuredOutputParser;

    @Mock
    private AiProperties aiProperties;

    @Mock
    private ModelPriceCache modelPriceCache;

    /** 真实 ObjectMapper：{@code toDrafts} 的映射与 steps 序列化必须是真实现，mock 掉就测不到契约。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CaseGenerateHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CaseGenerateHandler(testPointMapper, apiOperationMapper, docSectionMapper,
                testCaseMapper, promptTemplateService, promptRenderer, loggingLlmProvider,
                jsonSchemaValidator, structuredOutputParser, objectMapper, aiProperties,
                modelPriceCache);

        // 共用桩用 lenient：失败用例（2/3）根本走不到模板与渲染那几步，
        // 严格桩会把"没用到"误报成 UnnecessaryStubbingException。
        PromptTemplate template = new PromptTemplate();
        template.setCode(TEMPLATE_CODE);
        template.setVersion(1);
        template.setContent("测试点：{{test_point}}\n上下文：{{api_spec}}\n"
                + "类型：{{case_types}}\n规则：{{priority_rules}}\n参考：{{few_shot}}");
        template.setOutputSchema(SCHEMA_PATH);
        lenient().when(promptTemplateService.activeOf(TEMPLATE_CODE)).thenReturn(template);
        lenient().when(promptRenderer.render(anyString(), anyMap())).thenReturn("rendered-case-prompt");
        lenient().when(jsonSchemaValidator.readSchemaText(SCHEMA_PATH)).thenReturn("{}");
        // 温度走真实默认值（generate=0.4），避免每个用例都手写一遍
        lenient().when(aiProperties.getTemperature()).thenReturn(new AiProperties.Temperature());
    }

    // ------------------------------------------------------------------ 夹具

    private static GenTask task() {
        GenTask task = new GenTask();
        task.setId(TASK_ID);
        task.setTaskNo(TASK_NO);
        task.setScene(TaskScene.CASE_GENERATE.code());
        task.setDocId(DOC_ID);
        task.setModel(MODEL);
        task.setStatus("RUNNING");
        return task;
    }

    private static GenTaskItem item(Long testPointId, String unitRef, Integer attempts) {
        GenTaskItem item = new GenTaskItem();
        item.setId(ITEM_ID);
        item.setTaskId(TASK_ID);
        item.setTestPointId(testPointId);
        item.setUnitRef(unitRef);
        item.setAttempts(attempts);
        item.setStatus("RUNNING");
        return item;
    }

    private static TestPoint testPoint() {
        TestPoint point = new TestPoint();
        point.setId(TEST_POINT_ID);
        point.setDocId(DOC_ID);
        point.setTitle("创建用户时 name 必填");
        point.setCategory(CaseType.FUNCTIONAL.code());
        point.setRiskLevel("HIGH");
        point.setDescription("验证缺少 name 时返回校验失败");
        point.setSourceRef("POST /api/users");
        point.setStatus("ACTIVE");
        return point;
    }

    private static LlmResponse response(String content) {
        return LlmResponse.builder()
                .content(content)
                .usage(new LlmUsage(120, 80))
                .latencyMs(1234L)
                .model(MODEL)
                .build();
    }

    /** 把 JSON 数组文本包成 {@code parseArrayWithMeta} 的返回值（真实 ArrayNode，真实映射）。 */
    private ArrayNode arrayOf(String json) {
        try {
            return (ArrayNode) objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("测试夹具里的 JSON 写错了：" + json, ex);
        }
    }

    private static final String TWO_CASES = "["
            + "{\"title\":\"缺少 name 时创建用户被拒绝\",\"precondition\":\"服务已启动\","
            + "\"steps\":[\"构造不含 name 的请求\",\"调用 POST /api/users\"],"
            + "\"expected\":\"返回 code=40001 且 message 含 name\",\"priority\":\"P0\","
            + "\"caseType\":\"EXCEPTION\"},"
            + "{\"title\":\"name 为 64 个字符时创建成功\",\"precondition\":\"无\","
            + "\"steps\":[\"构造 name 为 64 个 a 的请求\"],"
            + "\"expected\":\"返回 HTTP 200 且 code=0\",\"priority\":\"P1\","
            + "\"caseType\":\"BOUNDARY\"}]";

    private static final String THREE_CASES = "["
            + "{\"title\":\"用例一\",\"precondition\":\"无\",\"steps\":[\"步骤\"],"
            + "\"expected\":\"结果一\",\"priority\":\"P0\",\"caseType\":\"FUNCTIONAL\"},"
            + "{\"title\":\"用例二\",\"precondition\":\"无\",\"steps\":[\"步骤\"],"
            + "\"expected\":\"结果二\",\"priority\":\"P1\",\"caseType\":\"BOUNDARY\"},"
            + "{\"title\":\"用例三\",\"precondition\":\"无\",\"steps\":[\"步骤\"],"
            + "\"expected\":\"结果三\",\"priority\":\"P2\",\"caseType\":\"EXCEPTION\"}]";

    // ------------------------------------------------------------------ 用例
    @Test
    @DisplayName("正常路径：2 条 draft → 落库 2 条，produced/token/cost/latency 全部取真实响应")
    void generatesAndPersistsCases() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(loggingLlmProvider.chat(any(LlmRequest.class))).thenReturn(response("{\"cases\":[]}"));
        when(structuredOutputParser.parseArrayWithMeta(anyString(), eq(SCHEMA_PATH)))
                .thenReturn(new StructuredOutputParser.ParseResult(arrayOf(TWO_CASES), false, false));
        when(modelPriceCache.costMicro(MODEL, new LlmUsage(120, 80))).thenReturn(42L);

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.produced()).isEqualTo(2);
        assertThat(outcome.degraded()).isZero();
        // T07 验收②的硬点：这四个值必须来自本次响应，不能是 0
        assertThat(outcome.promptTokens()).isEqualTo(120);
        assertThat(outcome.completionTokens()).isEqualTo(80);
        assertThat(outcome.costMicro()).isEqualTo(42L);
        assertThat(outcome.latencyMs()).isEqualTo(1234);
        assertThat(outcome.errorCode()).isNull();

        ArgumentCaptor<List<TestCase>> captor = batchCaptor();
        verify(testCaseMapper).upsertBatch(captor.capture());
        List<TestCase> rows = captor.getValue();
        assertThat(rows).hasSize(2);

        TestCase first = rows.get(0);
        assertThat(first.getTaskId()).isEqualTo(TASK_ID);
        assertThat(first.getTaskItemId()).isEqualTo(ITEM_ID);
        assertThat(first.getTestPointId()).isEqualTo(TEST_POINT_ID);
        assertThat(first.getDocId()).isEqualTo(DOC_ID);
        assertThat(first.getSeqNo()).isEqualTo(1);
        assertThat(rows.get(1).getSeqNo()).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(CaseStatus.DRAFT.code());
        assertThat(first.getPriority()).isEqualTo("P0");
        assertThat(first.getCaseType()).isEqualTo("EXCEPTION");
        assertThat(first.getPromptVersion()).isEqualTo("case_generate.v1");
        assertThat(first.getModel()).isEqualTo(MODEL);
        // schema 里没有 confidence 字段（additionalProperties=false），落库值只能由服务端定
        assertThat(first.getConfidence()).isEqualByComparingTo(new BigDecimal("1.000"));
        // steps 是 JSON **数组**字符串，元素带 no/action/data（DDL 注释的既定形状）
        assertThat(first.getSteps()).isEqualTo("[{\"no\":1,\"action\":\"构造不含 name 的请求\","
                + "\"data\":\"\"},{\"no\":2,\"action\":\"调用 POST /api/users\",\"data\":\"\"}]");
        // 内容哈希是 64 位小写十六进制（sha256）
        assertThat(first.getContentHash()).matches("[0-9a-f]{64}");

        verify(testCaseMapper).deleteStaleSeqNo(ITEM_ID, 2);
    }

    @Test
    @DisplayName("测试点不存在：failed(E_NOT_FOUND)，且不抛异常、不调模型、不落库")
    void missingTestPointFailsWithoutThrowing() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(null);

        // 预期内失败必须收成返回值而不是异常：批量执行要汇总 N 条结果，抛异常只能表达第一条
        ItemOutcome outcome = assertDoesNotThrow(
                () -> handler.execute(task(), item(TEST_POINT_ID, null, 0)));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.produced()).isZero();
        assertThat(outcome.errorCode()).isEqualTo("E_NOT_FOUND");
        assertThat(outcome.errorMsg()).contains("测试点不存在").contains(String.valueOf(TEST_POINT_ID));
        assertThat(outcome.latencyMs()).isZero();

        verifyNoInteractions(loggingLlmProvider, testCaseMapper);
    }

    @Test
    @DisplayName("既无 testPointId 又无 testpoint:<id> 的 unit_ref：failed(E_PARAM_INVALID)")
    void itemWithoutTestPointRefFails() {
        ItemOutcome outcome = handler.execute(task(), item(null, "operation:createUser", 0));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("E_PARAM_INVALID");
        assertThat(outcome.errorMsg()).isEqualTo("任务子项未关联测试点");
        verifyNoInteractions(testPointMapper, loggingLlmProvider, testCaseMapper);
    }

    @Test
    @DisplayName("模型空产出：failed(E_LLM_EMPTY)，不能当「成功产出 0 条」")
    void emptyModelOutputIsFailure() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(loggingLlmProvider.chat(any(LlmRequest.class))).thenReturn(response("{\"cases\":[]}"));
        when(structuredOutputParser.parseArrayWithMeta(anyString(), eq(SCHEMA_PATH)))
                .thenReturn(new StructuredOutputParser.ParseResult(arrayOf("[]"), false, false));

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.produced()).isZero();
        assertThat(outcome.errorCode()).isEqualTo("E_LLM_EMPTY");
        assertThat(outcome.errorMsg()).isEqualTo("模型未产出任何用例");
        // 失败也要带耗时：它是"模型慢还是一条没给"的唯一区分依据
        assertThat(outcome.latencyMs()).isEqualTo(1234);
        verify(testCaseMapper, never()).upsertBatch(any());
        verify(testCaseMapper, never()).deleteStaleSeqNo(any(), anyInt());
    }

    @Test
    @DisplayName("attemptNo = item.attempts + 1（第 3 次尝试 → attemptNo=3），直接锁住台账口径")
    void attemptNoFollowsItemAttempts() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(loggingLlmProvider.chat(any(LlmRequest.class))).thenReturn(response("{}"));
        when(structuredOutputParser.parseArrayWithMeta(anyString(), eq(SCHEMA_PATH)))
                .thenReturn(new StructuredOutputParser.ParseResult(arrayOf(TWO_CASES), false, false));

        handler.execute(task(), item(TEST_POINT_ID, null, 2));

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(loggingLlmProvider).chat(captor.capture());
        LlmRequest request = captor.getValue();
        assertThat(request.getAttemptNo()).isEqualTo(3);
        // 记账三元组必须齐：缺一个 llm_call_log 就无法按任务/子项归因
        assertThat(request.getTaskId()).isEqualTo(TASK_ID);
        assertThat(request.getTaskItemId()).isEqualTo(ITEM_ID);
        assertThat(request.getScene()).isEqualTo(TaskScene.CASE_GENERATE.code());
        // 5 个占位符都要给全，否则 PromptRenderer 抛 40001（这里用桩绕过了渲染，
        // 所以只校验"渲染被调用过且入参是 5 个变量"）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> vars =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(promptRenderer).render(anyString(), vars.capture());
        assertThat(vars.getValue())
                .containsKeys("test_point", "api_spec", "case_types", "priority_rules", "few_shot");
    }

    @Test
    @DisplayName("重跑条数变少：第一次 3 条 → 第二次 2 条，第二次必须删 seq_no > 2 的残留")
    void rerunTrimsStaleSeqNo() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(loggingLlmProvider.chat(any(LlmRequest.class))).thenReturn(response("{}"));
        when(structuredOutputParser.parseArrayWithMeta(anyString(), eq(SCHEMA_PATH)))
                .thenReturn(new StructuredOutputParser.ParseResult(arrayOf(THREE_CASES), false, false))
                .thenReturn(new StructuredOutputParser.ParseResult(arrayOf(TWO_CASES), false, false));

        ItemOutcome first = handler.execute(task(), item(TEST_POINT_ID, null, 0));
        ItemOutcome second = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(first.produced()).isEqualTo(3);
        assertThat(second.produced()).isEqualTo(2);
        // 两次都按"本次条数"清理尾部：剩下的就是 seq_no 1..N
        verify(testCaseMapper).deleteStaleSeqNo(ITEM_ID, 3);
        verify(testCaseMapper).deleteStaleSeqNo(ITEM_ID, 2);
        // upsert 调了两次（每次一条语句写完整批），不是逐条 insert
        verify(testCaseMapper, times(2)).upsertBatch(any());
        verify(testCaseMapper, never()).insert(any(TestCase.class));
    }

    /** 捕获批量写入的行（泛型擦除，需显式转型）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<TestCase>> batchCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
