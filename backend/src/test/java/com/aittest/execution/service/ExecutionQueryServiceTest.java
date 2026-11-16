package com.aittest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.execution.domain.ExecutionResult;
import com.aittest.execution.domain.ExecutionRun;
import com.aittest.execution.dto.ExecutionResultVO;
import com.aittest.execution.dto.ExecutionRunDetailVO;
import com.aittest.execution.dto.ExecutionRunVO;
import com.aittest.execution.mapper.ExecutionResultMapper;
import com.aittest.execution.mapper.ExecutionRunMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.system.domain.FailureCategory;
import com.aittest.system.mapper.FailureCategoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 执行批次只读查询的契约测试（纯 Mockito，不连库）。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li><b>任务单号用 N+1 逐个查</b>：一页 20 条就是 21 次查询，且这些查询之间没有一致性
 *       （查到一半任务被删，同一页里有的行有单号、有的没有），页面上出现"同一批数据的
 *       任务列一半有一半空"；</li>
 *   <li><b>任务被删后 {@code taskNo} 返回空串而不是 null</b>：前端按 null 渲染"—"，
 *       空串会渲染成空白单元格，看起来像"数据丢了"；</li>
 *   <li><b>状态分布的 label 现查字典表</b>：多一次 IO，且字典被停用后标签变空——
 *       三值是封闭枚举，文案必须在代码里定死；</li>
 *   <li><b>脏筛选值静默返回空列表</b>：用户会以为"筛对了但这批没数据"，排查方向相反；</li>
 *   <li><b>批次不存在时 {@code /results} 静默返回空列表</b>：错误 id 与"这批 0 条结果"
 *       在页面上完全一样；</li>
 *   <li><b>keyword 搜不到关联任务号</b>：排查时手上更常见的其实是被测任务的单号
 *       （{@code T20261116-000004}），搜不到会被读成"这个任务我没导过"——比重名搜错更难纠正。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class ExecutionQueryServiceTest {

    /** 批次主键。 */
    private static final Long RUN_ID = 6_000_001L;

    /** 任务主键。 */
    private static final Long TASK_ID = 7_100L;

    /** 任务单号。 */
    private static final String TASK_NO = "T20261116-000001";

    @Mock
    private ExecutionRunMapper executionRunMapper;

    @Mock
    private ExecutionResultMapper executionResultMapper;

    @Mock
    private FailureCategoryMapper failureCategoryMapper;

    @Mock
    private GenTaskMapper genTaskMapper;

    private ExecutionQueryService service;

    /**
     * 安装 lambda 列缓存（{@code LambdaQueryWrapper<ExecutionRun>} 需要解析列名）。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        service = new ExecutionQueryService(executionRunMapper, executionResultMapper,
                failureCategoryMapper, genTaskMapper);
    }

    @Test
    @DisplayName("列表的 taskNo 用一次 selectBatchIds 批量补齐（一页 N 条不能查 N+1 次）")
    void fillsTaskNoInOneBatch() {
        ExecutionRun withTask = run(RUN_ID, TASK_ID);
        ExecutionRun withoutTask = run(RUN_ID + 1, null);
        when(executionRunMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(withTask, withoutTask), 2L));
        when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of(genTask()));

        PageResult<ExecutionRunVO> result = service.page(null, null, new PageQuery());

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getRecords().get(0).taskId()).isEqualTo(TASK_ID);
        assertThat(result.getRecords().get(0).taskNo()).isEqualTo(TASK_NO);
        // task_id 为 null 的批次（任务已删）不能因此触发查询，也不能凭空给个单号
        assertThat(result.getRecords().get(1).taskNo()).isNull();
        // 关键断言：一页 2 条只查 1 次任务，且只问非 null 的 taskId
        verify(genTaskMapper, times(1)).selectBatchIds(any());
    }

    @Test
    @DisplayName("任务已被删（SET NULL 后查不到）时 taskNo 返回 null 而不是空串")
    void returnsNullTaskNoWhenTaskGone() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run(RUN_ID, TASK_ID));
        when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of());
        when(executionResultMapper.selectCategoryDistribution(RUN_ID)).thenReturn(List.of());

        ExecutionRunDetailVO detail = service.detail(RUN_ID);

        assertThat(detail.taskId()).isEqualTo(TASK_ID);
        assertThat(detail.taskNo()).isNull();
    }

    @Test
    @DisplayName("状态分布恒 3 项、0 也保留，label 用枚举文案（不现查字典，停用字典不该让标签变空）")
    void statusCountsAlwaysThree() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run(RUN_ID, TASK_ID));
        when(genTaskMapper.selectBatchIds(any())).thenReturn(List.of(genTask()));
        when(executionResultMapper.selectCategoryDistribution(RUN_ID)).thenReturn(List.of());

        ExecutionRunDetailVO detail = service.detail(RUN_ID);

        assertThat(detail.statusCounts()).hasSize(3);
        assertThat(detail.statusCounts())
                .extracting(ExecutionRunDetailVO.Point::label)
                .containsExactly("通过", "失败", "已跳过");
        assertThat(detail.statusCounts())
                .extracting(ExecutionRunDetailVO.Point::count)
                .containsExactly(7, 2, 1);
        // 0 条记录的失败分类分布：空数组，不是 null（前端不必判空）
        assertThat(detail.categoryDistribution()).isEmpty();
    }

    @Test
    @DisplayName("批次不存在抛 40401，而不是返回空详情让页面显示 0 条")
    void detailOfMissingRunIsNotFound() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(RUN_ID))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_NOT_FOUND));
    }

    @Test
    @DisplayName("明细列表：status 脏值抛 40001 并列出可选值（静默空列表会被误读成『这批没数据』）")
    void rejectsDirtyStatus() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run(RUN_ID, TASK_ID));
        when(failureCategoryMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.results(RUN_ID, "NOPE", null, new PageQuery()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.E_PARAM_INVALID))
                .hasMessageContaining("PASSED/FAILED/SKIPPED");
    }

    @Test
    @DisplayName("明细列表：failureCategory 翻出中文 label，未归因的行 label 为 null")
    void fillsFailureLabel() {
        when(executionRunMapper.selectById(RUN_ID)).thenReturn(run(RUN_ID, TASK_ID));
        when(failureCategoryMapper.selectList(any())).thenReturn(List.of(category("TIMEOUT", "超时")));
        when(executionResultMapper.selectPage(any(), any()))
                .thenReturn(pageOf(List.of(result("FAILED", "TIMEOUT"), result("PASSED", null)), 2L));

        PageResult<ExecutionResultVO> result = service.results(RUN_ID, null, null, new PageQuery());

        assertThat(result.getRecords().get(0).failureLabel()).isEqualTo("超时");
        assertThat(result.getRecords().get(1).failureCategory()).isNull();
        assertThat(result.getRecords().get(1).failureLabel()).isNull();
    }

    @Test
    @DisplayName("keyword 第四处匹配关联任务号：走子查询 + 参数绑定，四处一律不转义")
    void keywordMatchesTaskNoBySubquery() {
        when(executionRunMapper.selectPage(any(), any())).thenReturn(pageOf(List.of(), 0L));

        service.page(null, "T20261116-000004", new PageQuery());

        ArgumentCaptor<LambdaQueryWrapper<ExecutionRun>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(executionRunMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<ExecutionRun> wrapper = captor.getValue();

        String sql = wrapper.getSqlSegment();
        // 表名写错（gen_tasks / gen_task_info）在这里就会被抓住，而不是等联调时"搜不到"
        assertThat(sql).contains("gen_task").contains("task_no LIKE");
        // 原有三处不能被这次改动挤掉：批次号/名称/文件名照旧可搜
        assertThat(sql).contains("run_no").contains("name").contains("file_name");
        // 任务号是绑定参数（PreparedStatement 值），不是拼进 SQL 的字符串
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("%T20261116-000004%")
                // 本轮四处统一不做通配符转义：值里出现 \% 就说明有人只给这一处加了转义，
                // 前三处仍把 % 当通配符，行为反而更不一致
                .noneMatch(value -> value instanceof String text && text.contains("\\%"));
    }

    /**
     * 构造批次实体。
     *
     * @param id     批次主键
     * @param taskId 任务主键（可 null）
     * @return 实体
     */
    private static ExecutionRun run(Long id, Long taskId) {
        ExecutionRun run = new ExecutionRun();
        run.setId(id);
        run.setRunNo("R20261116-000001");
        run.setTaskId(taskId);
        run.setName("回归批次");
        run.setSourceType("JUNIT_XML");
        run.setFileName("result.xml");
        run.setTotalCount(10);
        run.setPassedCount(7);
        run.setFailedCount(2);
        run.setSkippedCount(1);
        run.setMappedCount(5);
        run.setDurationMs(1234L);
        run.setCreatedAt(LocalDateTime.of(2026, 11, 16, 10, 0));
        return run;
    }

    /**
     * 构造任务实体（只需 id 与 taskNo）。
     *
     * @return 任务
     */
    private static GenTask genTask() {
        GenTask task = new GenTask();
        task.setId(TASK_ID);
        task.setTaskNo(TASK_NO);
        return task;
    }

    /**
     * 构造失败分类字典行。
     *
     * @param code  码
     * @param label 文案
     * @return 字典行
     */
    private static FailureCategory category(String code, String label) {
        FailureCategory category = new FailureCategory();
        category.setCode(code);
        category.setLabel(label);
        return category;
    }

    /**
     * 构造明细实体。
     *
     * @param status   状态
     * @param category 失败分类（可 null）
     * @return 明细
     */
    private static ExecutionResult result(String status, String category) {
        ExecutionResult row = new ExecutionResult();
        row.setId(RUN_ID + 100);
        row.setRunId(RUN_ID);
        row.setClassName("com.aittest.generated.GeneratedCase_001");
        row.setCaseName("case_001");
        row.setStatus(status);
        row.setDurationMs(10L);
        row.setFailureCategory(category);
        row.setTriageSource("RULE");
        return row;
    }

    /**
     * 构造 MyBatis-Plus 分页结果（分页插件会填 records 与 total）。
     *
     * @param records 当页记录
     * @param total   总数
     * @param <T>     记录类型
     * @return 分页对象
     */
    private static <T> Page<T> pageOf(List<T> records, long total) {
        Page<T> page = new Page<>(1, 20);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }
}
