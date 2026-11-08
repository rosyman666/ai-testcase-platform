package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.aicore.prompt.PromptTemplate;
import com.aittest.aicore.prompt.PromptTemplateService;
import com.aittest.common.enums.CaseStatus;
import com.aittest.common.enums.CaseType;
import com.aittest.common.enums.TaskScene;
import com.aittest.config.AiProperties;
import com.aittest.doc.mapper.ApiOperationMapper;
import com.aittest.doc.mapper.DocSectionMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.orchestrator.CaseGenerateOrchestrator;
import com.aittest.generation.orchestrator.DegradeLevel;
import com.aittest.generation.orchestrator.GenerationContext;
import com.aittest.generation.orchestrator.GenerationResult;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.CaseDraft;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.aittest.testpoint.domain.TestPoint;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * {@code CASE_GENERATE} 的 <b>I/O 适配契约</b>锁（T07-1 的六条语义原样保留，T07-2 只换协作者）。
 *
 * <p><b>本类守的纪律</b>（每条都对应一次真实事故或一个不可回退的契约）：</p>
 * <ol>
 *   <li><b>指标真回写</b>：token / 成本 / 耗时取编排层的累计值，不是全 0
 *       （抽点侧恒传 0 的历史遗留不能复制）；</li>
 *   <li><b>预期内失败不抛异常</b>（D-27）：没关联测试点、测试点被删、模型空产出，
 *       一律 {@link ItemOutcome#failed}，否则批量执行只能表达"第一条失败"；</li>
 *   <li><b>空产出不算成功</b>：返回 {@code produced=0} 会让任务看起来"跑完了"；</li>
 *   <li><b>{@code attemptNo} 跟 item.attempts 走</b>：它是"首次通过率"的唯一来源；</li>
 *   <li><b>重跑要清尾部残留</b>：上次 3 条、这次 2 条时删掉 {@code seq_no > 2}；</li>
 *   <li><b>新生成一律 DRAFT</b>，且 {@code status} 不在 upsert 的更新列里。</li>
 *   <li><b>T07-2 新增</b>：宽松档落 {@code confidence=0.500} 且 {@code degraded=1}；
 *       宽松档空产出的错误码是 {@code E_SCHEMA_INVALID}（不是 {@code E_LLM_EMPTY}）。</li>
 * </ol>
 *
 * <p><b>本类不再断言"5 个占位符齐不齐"</b>：渲染已经搬进编排层，
 * 它由 {@code CaseGenerateOrchestratorTest#generatePromptGetsAllFivePlaceholders} 守。
 * 这里改为断言<b>交给编排层的 {@link GenerationContext} 字段是否查对</b>——
 * 这才是 handler 现在唯一的职责边界。</p>
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
    private CaseGenerateOrchestrator caseGenerateOrchestrator;

    /** 真实 ObjectMapper：steps 序列化必须是真实现，mock 掉就测不到落库形状。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiProperties aiProperties;

    private CaseGenerateHandler handler;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        handler = new CaseGenerateHandler(testPointMapper, apiOperationMapper, docSectionMapper,
                testCaseMapper, promptTemplateService, caseGenerateOrchestrator, objectMapper,
                aiProperties);

        // 共用桩用 lenient：失败用例（2/3）根本走不到模板那一步
        PromptTemplate template = new PromptTemplate();
        template.setCode(TEMPLATE_CODE);
        template.setVersion(1);
        template.setContent("占位符已搬到编排层，这里只需要 code/version");
        template.setOutputSchema("classpath:schema/testcase.schema.json");
        lenient().when(promptTemplateService.activeOf(TEMPLATE_CODE)).thenReturn(template);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    @DisplayName("正常路径：NORMAL 档 2 条 draft → 落库 2 条，指标全部来自编排层累计值")
    void generatesAndPersistsCases() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(normalResult(TWO_CASES, 120, 80, 42L, 1234));

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.produced()).isEqualTo(2);
        assertThat(outcome.degraded()).isZero();
        // T07 验收②的硬点：这四个值必须来自编排层，不能是 0
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
        // schema 里没有 confidence 字段（additionalProperties=false），正常档只能由服务端定 1.000
        assertThat(first.getConfidence()).isEqualByComparingTo(new BigDecimal("1.000"));
        // steps 是 JSON **数组**字符串，元素带 no/action/data（DDL 注释的既定形状）
        assertThat(first.getSteps()).isEqualTo("[{\"no\":1,\"action\":\"构造不含 name 的请求\","
                + "\"data\":\"\"},{\"no\":2,\"action\":\"调用 POST /api/users\",\"data\":\"\"}]");
        // 内容哈希是 64 位小写十六进制（sha256）
        assertThat(first.getContentHash()).matches("[0-9a-f]{64}");

        verify(testCaseMapper).deleteStaleSeqNo(ITEM_ID, 2);
    }

    @Test
    @DisplayName("交给编排层的上下文：测试点/文档/尝试次数/版本/上下文/素材全部查对")
    void contextCarriesEverythingTheOrchestratorNeeds() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(testCaseMapper.selectFewShot(DOC_ID, 5)).thenReturn(List.of(
                fewShot("name 为 64 个字符时创建成功", "BOUNDARY", "返回 HTTP 200 且 code=0\n且落库一条")));
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(normalResult(TWO_CASES, 10, 5, 0L, 20));

        handler.execute(task(), item(TEST_POINT_ID, null, 2));

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(caseGenerateOrchestrator).generate(captor.capture());
        GenerationContext context = captor.getValue();
        assertThat(context.taskId()).isEqualTo(TASK_ID);
        assertThat(context.taskItemId()).isEqualTo(ITEM_ID);
        assertThat(context.docId()).isEqualTo(DOC_ID);
        assertThat(context.testPointId()).isEqualTo(TEST_POINT_ID);
        // attempts=2 表示已经失败过 2 次 → 本次是第 3 次
        assertThat(context.attemptNo()).isEqualTo(3);
        assertThat(context.promptVersion()).isEqualTo("case_generate.v1");
        assertThat(context.testPointText()).contains("标题：创建用户时 name 必填").contains("风险等级：HIGH");
        // unit_ref/operation 都为空 → api_spec 必须是空串（绝不编造接口上下文）
        assertThat(context.apiSpec()).isEmpty();
        assertThat(context.caseTypes()).contains("BOUNDARY").contains("SECURITY");
        assertThat(context.priorityRules()).contains("P0/P1/P2/P3");
        // few-shot：一行一条，只给 caseType/title/expected，且换行被压平
        assertThat(context.fewShot())
                .isEqualTo("- [BOUNDARY] 标题：name 为 64 个字符时创建成功；"
                        + "预期：返回 HTTP 200 且 code=0 且落库一条");
    }

    @Test
    @DisplayName("测试点不存在：failed(E_NOT_FOUND)，且不抛异常、不调编排层、不落库")
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

        verifyNoInteractions(caseGenerateOrchestrator, testCaseMapper);
    }

    @Test
    @DisplayName("既无 testPointId 又无 testpoint:<id> 的 unit_ref：failed(E_PARAM_INVALID)")
    void itemWithoutTestPointRefFails() {
        ItemOutcome outcome = handler.execute(task(), item(null, "operation:createUser", 0));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("E_PARAM_INVALID");
        assertThat(outcome.errorMsg()).isEqualTo("任务子项未关联测试点");
        verifyNoInteractions(testPointMapper, caseGenerateOrchestrator, testCaseMapper);
    }

    @Test
    @DisplayName("正常档空产出：failed(E_LLM_EMPTY)，不能当「成功产出 0 条」")
    void emptyModelOutputIsFailure() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(normalResult(List.of(), 0, 0, 0L, 1234));

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
    @DisplayName("宽松档空产出：failed(E_SCHEMA_INVALID)，不能记成「模型没说话」")
    void lenientWithoutAnyDraftFailsWithSchemaInvalid() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(lenientResult(List.of(), 0, 0, 0L, 321));

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("E_SCHEMA_INVALID");
        assertThat(outcome.errorMsg()).contains("宽松解析后仍无可保留的用例");
        assertThat(outcome.latencyMs()).isEqualTo(321);
        verify(testCaseMapper, never()).upsertBatch(any());
    }

    @Test
    @DisplayName("宽松档有产出：degraded=1 且 confidence=0.500，指标照旧回写")
    void lenientResultIsPersistedWithHalfConfidence() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(lenientResult(ONE_CASE, 33, 22, 7L, 456));

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.produced()).isEqualTo(1);
        // 宽松档必须让 worker 把 gen_task_item.degraded 置 1（否则库里看不出这批是降级产出的）
        assertThat(outcome.degraded()).isEqualTo(1);
        assertThat(outcome.promptTokens()).isEqualTo(33);
        assertThat(outcome.completionTokens()).isEqualTo(22);
        assertThat(outcome.costMicro()).isEqualTo(7L);
        assertThat(outcome.latencyMs()).isEqualTo(456);

        ArgumentCaptor<List<TestCase>> captor = batchCaptor();
        verify(testCaseMapper).upsertBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getConfidence())
                .isEqualByComparingTo(new BigDecimal("0.500"));
        verify(testCaseMapper).deleteStaleSeqNo(ITEM_ID, 1);
    }

    @Test
    @DisplayName("few-shot 素材查询失败：不失败，按空串继续（素材是锦上添花，不是主料）")
    void fewShotQueryFailureDoesNotFailTheItem() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(testCaseMapper.selectFewShot(DOC_ID, 5)).thenThrow(new RuntimeException("拿不到连接"));
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(normalResult(ONE_CASE, 1, 1, 0L, 5));

        ItemOutcome outcome = handler.execute(task(), item(TEST_POINT_ID, null, 0));

        assertThat(outcome.success()).isTrue();
        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(caseGenerateOrchestrator).generate(captor.capture());
        // 空串而不是 null / "无"：模板里那段"参考范例"自然留白
        assertThat(captor.getValue().fewShot()).isEmpty();
    }

    @Test
    @DisplayName("编排层抛异常（上游 5xx）：原样冒泡给 worker 退避重试，不在这里转成 failed")
    void orchestratorExceptionPropagates() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenThrow(new IllegalStateException("上游炸了"));

        assertThatThrownBy(() -> handler.execute(task(), item(TEST_POINT_ID, null, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("上游炸了");
        verify(testCaseMapper, never()).upsertBatch(any());
    }

    @Test
    @DisplayName("重跑条数变少：第一次 3 条 → 第二次 2 条，第二次必须删 seq_no > 2 的残留")
    void rerunTrimsStaleSeqNo() {
        when(testPointMapper.selectById(TEST_POINT_ID)).thenReturn(testPoint());
        when(caseGenerateOrchestrator.generate(any(GenerationContext.class)))
                .thenReturn(normalResult(THREE_CASES, 1, 1, 0L, 5))
                .thenReturn(normalResult(TWO_CASES, 1, 1, 0L, 5));

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

    private static TestCase fewShot(String title, String caseType, String expected) {
        TestCase sample = new TestCase();
        sample.setTitle(title);
        sample.setCaseType(caseType);
        sample.setExpected(expected);
        return sample;
    }

    private static GenerationResult normalResult(List<CaseDraft> drafts, int promptTokens,
                                                 int completionTokens, long costMicro, int latencyMs) {
        return new GenerationResult(drafts, promptTokens, completionTokens, costMicro, latencyMs,
                DegradeLevel.NORMAL, "正常档：模型输出一次通过解析与结构校验", false, 0);
    }

    private static GenerationResult lenientResult(List<CaseDraft> drafts, int promptTokens,
                                                  int completionTokens, long costMicro, int latencyMs) {
        return new GenerationResult(drafts, promptTokens, completionTokens, costMicro, latencyMs,
                DegradeLevel.LENIENT, "宽松档：模型输出被截断，丢弃不完整元素后保留 1 条", true, 0);
    }

    private static CaseDraft draft(String title, String precondition, List<String> steps,
                                   String expected, String priority, String caseType) {
        return new CaseDraft(title, precondition, steps, expected, priority, caseType);
    }

    private static final List<CaseDraft> ONE_CASE = List.of(draft(
            "缺少 name 时创建用户被拒绝", "服务已启动",
            List.of("构造不含 name 的请求"), "返回 code=40001 且 message 含 name", "P0", "EXCEPTION"));

    private static final List<CaseDraft> TWO_CASES = List.of(
            draft("缺少 name 时创建用户被拒绝", "服务已启动",
                    List.of("构造不含 name 的请求", "调用 POST /api/users"),
                    "返回 code=40001 且 message 含 name", "P0", "EXCEPTION"),
            draft("name 为 64 个字符时创建成功", "无",
                    List.of("构造 name 为 64 个 a 的请求"),
                    "返回 HTTP 200 且 code=0", "P1", "BOUNDARY"));

    private static final List<CaseDraft> THREE_CASES = List.of(
            draft("用例一", "无", List.of("步骤"), "结果一", "P0", "FUNCTIONAL"),
            draft("用例二", "无", List.of("步骤"), "结果二", "P1", "BOUNDARY"),
            draft("用例三", "无", List.of("步骤"), "结果三", "P2", "EXCEPTION"));

    /** 捕获批量写入的行（泛型擦除，需显式转型）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<TestCase>> batchCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
