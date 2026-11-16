package com.aittest.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.IdGen;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionImportResult;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import com.aittest.execution.parser.CsvResultParser;
import com.aittest.execution.parser.JUnitXmlParser;
import com.aittest.execution.parser.ResultParser;
import com.aittest.execution.service.ExecutionImportService;
import com.aittest.execution.service.ExecutionWriteService;
import com.aittest.execution.service.JunitRefLookup;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testcase.mapper.TestCaseMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 「归因来源只出现在失败结果上」的验收用例：<b>走真实样例文件</b>
 * （{@code docs/samples/junit-result.xml}，4 通过 / 3 失败 / 1 跳过），
 * 经过真实解析器 + 真实规则归因 + 真实出参组装，断言整张列表的 {@code triageSource} 形态。
 *
 * <p><b>为什么不用手工构造的三行实体来验收</b>：手工实体只能证明 {@code from()} 里的
 * {@code if} 是通的，证明不了"导入流程真的把 8 行写成了那样"。这条链路里任何一个环节
 * 走偏（解析器漏 suite、规则把 ASSERTION 判成 TIMEOUT、写侧把 PASSED 也写了分类），
 * 用手工实体都看不见。样例文件是仓库里唯一的真实产物，直接用它验收。</p>
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>通过/跳过行也透出 {@code triageSource}</b>：导入时 {@code insertBatch} 显式给
 *       <b>每一行</b>绑 {@code 'RULE'}（该列 NOT NULL，必须绑值），所以库里 8 行全是
 *       {@code RULE}。原样出参会让 5 条从未归因过的结果在「归因来源」列里自称"规则判定"，
 *       详情弹窗渲染出「归因：未归因（规则）」；</li>
 *   <li><b>归一逻辑顺手把 FAILED 行也清空</b>：整列变「—」比偶尔显示错更难被发现，
 *       所以本用例<b>同时</b>断两侧，而不是只断"非 FAILED 为 null"；</li>
 *   <li><b>归因分布跑偏</b>：样例里三条失败分别是断言/超时/连接失败，正确分布是
 *       {@code ASSERTION=1 / TIMEOUT=1 / ENV=1}。若规则退化成"只看关键词"，三条会挤进
 *       同一个桶，饼图上看起来仍然"有数据"，只是永远只有一块。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExecutionTriageAcceptanceTest {

    /** 任务主键。 */
    private static final Long TASK_ID = 7_100L;

    /** 任务单号（导入出参回填）。 */
    private static final String TASK_NO = "T20261116-000004";

    /** 批次主键。 */
    private static final Long RUN_ID = 8_200L;

    /** 批次单号（由 mock 的 {@code IdGen} 直接给出）。 */
    private static final String RUN_NO = "R20261116-000001";

    /**
     * 样例文件路径。<b>相对 {@code backend/} 定位</b>：{@code mvn test} 的工作目录是模块根，
     * 仓库里的样例在上一级的 {@code docs/samples/} 下。
     */
    private static final Path SAMPLE = Path.of("..", "docs", "samples", "junit-result.xml");

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private ExecutionRunMapper executionRunMapper;

    @Mock
    private ExecutionResultMapper executionResultMapper;

    @Mock
    private IdGen idGen;

    private ExecutionImportService importService;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存：{@link JunitRefLookup} 内部会构造
     * {@code LambdaQueryWrapper}，纯单测没有启动期扫描，缺缓存会抛
     * {@code can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        // 写侧用真实实现：规则归因就在 saveResults 里，mock 掉等于把被测对象摘了
        ExecutionWriteService writeService =
                new ExecutionWriteService(executionRunMapper, executionResultMapper);
        List<ResultParser> parsers = List.of(new JUnitXmlParser(), new CsvResultParser());
        importService = new ExecutionImportService(genTaskMapper, new JunitRefLookup(testCaseMapper),
                writeService, idGen, parsers);
    }

    @Test
    @DisplayName("样例文件 4/3/1：只有 FAILED 行 triageSource 非空且为 RULE，PASSED/SKIPPED 为 null")
    void onlyFailedRowsExposeTriageSource() throws IOException {
        givenTaskExists();
        // 本用例不关心映射（映射率为 0 只是"这批没对上用例"，不影响归因）
        when(testCaseMapper.selectList(any())).thenReturn(List.of());
        givenRunInserted();
        assertThat(SAMPLE).as("样例文件缺失：需在 backend/ 目录下执行 mvn test").isRegularFile();
        MockMultipartFile file = new MockMultipartFile("file", "junit-result.xml", "text/xml",
                Files.readAllBytes(SAMPLE));

        ExecutionImportResult imported = importService.importResult(TASK_ID, null, file);

        // ① 先把"文件里到底是什么"钉住：4 通过 / 3 失败 / 1 跳过
        assertThat(imported.total()).isEqualTo(8);
        assertThat(imported.passed()).isEqualTo(4);
        assertThat(imported.failed()).isEqualTo(3);
        assertThat(imported.skipped()).isEqualTo(1);

        List<ExecutionResult> rows = captureInsertedRows();
        assertThat(rows).hasSize(8);

        // ② 库里那一行长什么样：8 行 triage_source 全是 'RULE'（insertBatch 显式绑值，
        //    该列 NOT NULL）。这正是"出参必须归一"的起因，不是 bug 现场
        assertThat(rows).allSatisfy(row -> assertThat(row.getTriageSource())
                .as("存储值：每行都被绑成 RULE，包括通过/跳过的那 5 行")
                .isEqualTo("RULE"));

        // ③ 真实规则归因的结果：三条失败各落到不同桶（与 categoryDistribution 一致）
        List<ExecutionResult> failedRows = rows.stream()
                .filter(row -> "FAILED".equals(row.getStatus()))
                .toList();
        assertThat(failedRows).hasSize(3);
        assertThat(failedRows).extracting(ExecutionResult::getFailureCategory)
                .as("断言/超时/连接失败必须各归各类；挤进同一个桶说明规则只看关键词")
                .containsExactlyInAnyOrder("ASSERTION", "TIMEOUT", "ENV");
        assertThat(rows).filteredOn(row -> !"FAILED".equals(row.getStatus()))
                .as("通过/跳过的行不该被写分类（写了饼图合计会大于失败数）")
                .allSatisfy(row -> assertThat(row.getFailureCategory()).isNull());

        // ④ 出参：GET /api/executions/{id}/results 拿到的整张列表
        List<ExecutionResultVO> vos = new ArrayList<>(rows.size());
        for (ExecutionResult row : rows) {
            vos.add(ExecutionResultVO.from(row, null));
        }

        // 反向断言：FAILED 行必须原样透出，不能被归一逻辑顺手清空
        assertThat(vos).filteredOn(vo -> "FAILED".equals(vo.status()))
                .hasSize(3)
                .allSatisfy(vo -> assertThat(vo.triageSource())
                        .as("失败行的归因来源不能变成 null，否则「归因来源」整列消失")
                        .isEqualTo("RULE"));
        // 正向断言：通过/跳过的 5 行恒为 null（DB 默认值不是语义）
        assertThat(vos).filteredOn(vo -> !"FAILED".equals(vo.status()))
                .hasSize(5)
                .allSatisfy(vo -> assertThat(vo.triageSource())
                        .as("没归因过的行不能说自己是规则判定")
                        .isNull());
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
     * 让取号 + 插批次成功（{@code generateWithRetry} 直接把单号喂给 action）。
     */
    @SuppressWarnings("unchecked")
    private void givenRunInserted() {
        when(idGen.generateWithRetry(eq(IdGen.NoType.RUN), any())).thenAnswer(invocation -> {
            Function<String, ExecutionRun> action = invocation.getArgument(1);
            return action.apply(RUN_NO);
        });
        when(executionRunMapper.insert(any(ExecutionRun.class))).thenAnswer(invocation -> {
            ExecutionRun run = invocation.getArgument(0);
            run.setId(RUN_ID);
            return 1;
        });
    }

    /**
     * 捕获真正发给 {@code insertBatch} 的明细（{@link ExecutionWriteService} 只在 8 条时会一次发完）。
     *
     * @return 明细列表（已按写入顺序拉平）
     */
    @SuppressWarnings("unchecked")
    private List<ExecutionResult> captureInsertedRows() {
        ArgumentCaptor<List<ExecutionResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(executionResultMapper, atLeastOnce()).insertBatch(captor.capture());
        List<ExecutionResult> rows = new ArrayList<>();
        for (List<ExecutionResult> batch : captor.getAllValues()) {
            rows.addAll(batch);
        }
        return rows;
    }
}
