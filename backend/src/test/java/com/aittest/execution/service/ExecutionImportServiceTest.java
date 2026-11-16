package com.aittest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionImportResult;
import com.aittest.execution.parser.CsvResultParser;
import com.aittest.execution.parser.JUnitXmlParser;
import com.aittest.execution.parser.ResultParser;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.mapper.TestCaseMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 导入编排的契约测试（纯 Mockito，不连库）。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>映射不上就整批失败</b>：结果是"映射率"这个指标的全部意义所在。映射不上必须
 *       留 {@code case_id = NULL} 并让 {@code mappedCount} 掉下来，而不是抛异常让用户无法导入，
 *       更不是随便挂一条用例（那会让"跑的是哪条用例"永远查不清）；</li>
 *   <li><b>映射按"类名包含"匹配</b>：{@code GeneratedCase_0011} 会误命中
 *       {@code GeneratedCase_001}，于是全部错位且不报错——所以只比全限定名的最后一段；</li>
 *   <li><b>计数写错</b>：总数/通过/失败/跳过/总耗时是页面上唯一的真值来源，
 *       错一个数就会让人把"通过率下降"归因到用例质量；</li>
 *   <li><b>不支持的格式走到解析器</b>：{@code .txt} 传上去应该 44002，而不是先解析再抛 50000。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExecutionImportServiceTest {

    /** 任务主键。 */
    private static final Long TASK_ID = 7_100L;

    /** 任务单号（导入出参回填，供前端 toast 使用）。 */
    private static final String TASK_NO = "T20261116-000001";

    /** 批次主键。 */
    private static final Long RUN_ID = 8_200L;

    /** 批次单号（由 mock 的 IdGen 直接给出，不验证取号逻辑本身）。 */
    private static final String RUN_NO = "R20260101-000001";

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private ExecutionWriteService executionWriteService;

    @Mock
    private IdGen idGen;

    private ExecutionImportService service;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存：本类会走
     * {@code new LambdaQueryWrapper<TestCase>().select(...)}，纯单测没有启动期扫描，
     * 缺缓存会抛 {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        List<ResultParser> parsers = List.of(new JUnitXmlParser(), new CsvResultParser());
        // 用真实的 JunitRefLookup + mock 的 mapper：映射口径（不兜底、去重）与导入共用同一份代码
        service = new ExecutionImportService(genTaskMapper, new JunitRefLookup(testCaseMapper),
                executionWriteService, idGen, parsers);
    }

    @Test
    @DisplayName("映射命中挂上 caseId、未命中留 null，且 mappedCount 如实小于 total（不阻断导入）")
    void mapsHitAndLeavesMissNull() {
        givenTaskExists();
        givenJunitRefs(caseOf(101L, "GeneratedCase_001"));
        givenRunInserted();
        // 0.123s + 0.456s = 579ms
        MockMultipartFile file = xmlFile("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                    <testsuite name="s">
                        <testcase classname="com.aittest.generated.GeneratedCase_001" name="case_001" time="0.123"/>
                        <testcase classname="com.aittest.generated.GeneratedCase_099" name="case_099" time="0.456">
                            <failure message="断言失败">java.lang.AssertionError: 失败</failure>
                        </testcase>
                    </testsuite>
                </testsuites>
                """);

        ExecutionImportResult result = service.importResult(TASK_ID, null, file);

        assertThat(result.runId()).isEqualTo(RUN_ID);
        assertThat(result.runNo()).isEqualTo(RUN_NO);
        // taskId/taskNo 供前端 toast 用「已导入到任务 T20261116-000001」，也供 #42 重跑映射
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.taskNo()).isEqualTo(TASK_NO);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.passed()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.mappedCount()).isEqualTo(1);

        List<ExecutionResult> rows = captureSavedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getCaseId()).isEqualTo(101L);
        assertThat(rows.get(0).getStatus()).isEqualTo("PASSED");
        assertThat(rows.get(0).getDurationMs()).isEqualTo(123L);
        assertThat(rows.get(1).getCaseId()).as("未命中的结果必须留空，不能猜一条用例").isNull();
        assertThat(rows.get(1).getStatus()).isEqualTo("FAILED");
        assertThat(rows.get(1).getFailureMessage()).isEqualTo("断言失败");
        // 归因来源本批恒为 RULE（DDL 默认值，下一批归因才改写）
        assertThat(rows.get(0).getTriageSource()).isEqualTo("RULE");
        // id 由应用侧生成：insertBatch 是手写 XML，绕开 MP 的主键填充
        assertThat(rows).allSatisfy(row -> assertThat(row.getId()).isNotNull());
    }

    @Test
    @DisplayName("计数与总耗时随批次一起写入（先插 0 再 UPDATE 会留『有明细没计数』的窗口）")
    void writesCountsWithRun() {
        givenTaskExists();
        givenJunitRefs();
        givenRunInserted();
        MockMultipartFile file = xmlFile("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="s">
                    <testcase classname="x.GeneratedCase_001" name="case_001" time="1.0"/>
                    <testcase classname="x.GeneratedCase_002" name="case_002" time="2.5">
                        <skipped message="环境未就绪"/>
                    </testcase>
                </testsuite>
                """);

        service.importResult(TASK_ID, "回归批次", file);

        // name 优先用用户填的；total=2 / passed=1 / failed=0 / skipped=1 / mapped=0 / 耗时=3500ms
        // taskId 必须落库：重跑映射（#42）与「这批结果属于哪个任务」都靠它
        verify(executionWriteService).insertRun(RUN_NO, TASK_ID, "回归批次", "JUNIT_XML", "result.xml",
                2, 1, 0, 1, 0, 3500L);
    }

    @Test
    @DisplayName("CSV 的 .csv 扩展名走 CSV 解析器（扩展名认错会让合法文件报 44001）")
    void importsCsv() {
        givenTaskExists();
        givenJunitRefs(caseOf(205L, "GeneratedCase_002"));
        givenRunInserted();
        MockMultipartFile file = new MockMultipartFile("file", "run.csv", "text/csv",
                ("class_name,case_name,status,duration_ms\n"
                        + "a.GeneratedCase_002,case_002,PASSED,42\n")
                        .getBytes(StandardCharsets.UTF_8));

        ExecutionImportResult result = service.importResult(TASK_ID, null, file);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.mappedCount()).isEqualTo(1);
        verify(executionWriteService).insertRun(any(), eq(TASK_ID), any(), eq("CSV"), eq("run.csv"),
                eq(1), eq(1), eq(0), eq(0), eq(1), eq(42L));
    }

    @Test
    @DisplayName("扩展名不支持抛 44002（而不是先解析再落成 50000）")
    void rejectsUnsupportedExtension() {
        givenTaskExists();
        MockMultipartFile file = new MockMultipartFile("file", "result.txt", "text/plain",
                "whatever".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importResult(TASK_ID, null, file))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_RESULT_UNSUPPORTED))
                .hasMessageContaining("result.txt");

        verify(executionWriteService, never()).insertRun(any(), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("空文件抛 44002 且文案自解释、与前端逐字一致（只描述现象会让人在原地重试同一个没下完的文件）")
    void rejectsEmptyFile() {
        givenTaskExists();
        MockMultipartFile file = new MockMultipartFile("file", "result.xml", "text/xml", new byte[0]);

        assertThatThrownBy(() -> service.importResult(TASK_ID, null, file))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_RESULT_UNSUPPORTED))
                // 逐字断言：这句话是跨端契约，改一个字前端就与服务端分叉
                .hasMessage("文件内容为空（0 字节），通常是导出或下载没有完成，请重新导出后再上传");
    }

    @Test
    @DisplayName("任务不存在抛 40401（不能把结果挂到一个已删除任务上）")
    void rejectsMissingTask() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(null);
        MockMultipartFile file = xmlFile("<testsuites/>");

        assertThatThrownBy(() -> service.importResult(TASK_ID, null, file))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_NOT_FOUND));
    }

    @Test
    @DisplayName("文件里 0 条结果抛 40003（空产出记成成功是本项目登记过的假绿）")
    void rejectsNoResults() {
        givenTaskExists();
        MockMultipartFile file = xmlFile("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites/>
                """);

        assertThatThrownBy(() -> service.importResult(TASK_ID, null, file))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_EMPTY_RESULT));
    }

    /**
     * 让 {@code genTaskMapper.selectById} 返回一个任务。
     */
    private void givenTaskExists() {
        GenTask task = new GenTask();
        task.setId(TASK_ID);
        task.setTaskNo(TASK_NO);
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task);
    }

    /**
     * 让 {@code test_case} 的映射查询返回给定用例。
     *
     * @param cases 用例（只带 id 与 junitRef）
     */
    private void givenJunitRefs(TestCase... cases) {
        when(testCaseMapper.selectList(any())).thenReturn(List.of(cases));
    }

    /**
     * 让取号 + 插批次成功（{@code generateWithRetry} 直接把单号喂给 action）。
     */
    @SuppressWarnings("unchecked")
    private void givenRunInserted() {
        when(idGen.generateWithRetry(eq(IdGen.NoType.RUN), any())).thenAnswer(invocation -> {
            Function<String, ExecutionRun> action = invocation.getArgument(1);
            return action.apply(RUN_NO);
        });
        when(executionWriteService.insertRun(eq(RUN_NO), any(), any(), any(), any(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong()))
                .thenAnswer(invocation -> {
                    ExecutionRun run = new ExecutionRun();
                    run.setId(RUN_ID);
                    run.setRunNo(RUN_NO);
                    run.setTaskId(invocation.getArgument(1));
                    run.setName(invocation.getArgument(2));
                    run.setSourceType(invocation.getArgument(3));
                    run.setFileName(invocation.getArgument(4));
                    return run;
                });
    }

    /**
     * 捕获 {@code saveResults} 收到的明细。
     *
     * @return 明细列表
     */
    @SuppressWarnings("unchecked")
    private List<ExecutionResult> captureSavedRows() {
        ArgumentCaptor<List<ExecutionResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(executionWriteService).saveResults(eq(RUN_ID), captor.capture(), anyList());
        return captor.getValue();
    }

    /**
     * 构造一个只带 id 与 junitRef 的用例（模拟 {@code select(id, junit_ref)} 的部分填充）。
     *
     * @param id  用例主键
     * @param ref 类名序号
     * @return 用例
     */
    private static TestCase caseOf(Long id, String ref) {
        TestCase entity = new TestCase();
        entity.setId(id);
        entity.setJunitRef(ref);
        return entity;
    }

    /**
     * 构造 XML 上传文件。
     *
     * @param xml XML 文本
     * @return MultipartFile
     */
    private static MockMultipartFile xmlFile(String xml) {
        return new MockMultipartFile("file", "result.xml", "text/xml",
                xml.getBytes(StandardCharsets.UTF_8));
    }
}
