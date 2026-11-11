package com.aittest.testcase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testcase.domain.CaseReview;
import com.aittest.testcase.domain.TestCase;
import com.aittest.testcase.dto.BatchReviewReq;
import com.aittest.testcase.dto.BatchReviewResult;
import com.aittest.testcase.dto.CaseUpdateReq;
import com.aittest.testcase.dto.ReviewReq;
import com.aittest.testcase.dto.TestCaseVO;
import com.aittest.testcase.mapper.CaseReviewMapper;
import com.aittest.testcase.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用例读取侧的契约测试。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li>{@code steps} 原样把 JSON 字符串丢给前端 → 前端每个单元格 parse 一次，
 *       且一条脏数据让整页白屏；</li>
 *   <li>列表也去查评审历史 → 一页 20 条变成 40~60 行额外读取；</li>
 *   <li>详情把历史按倒序排 → 时间线读起来像"先采纳又驳回"；</li>
 *   <li>分页读 {@code query.getSize()} 而不是 {@code sizeOrDefault()} → 绕过 100 上限；</li>
 *   <li>写操作返回内存里拼的 VO → {@code createdAt} 是 {@code null}，前端显示 1970 年。</li>
 * </ul>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CaseServiceTest {

    /** 用例主键。 */
    private static final Long CASE_ID = 8_000_001L;

    /** 所属任务。 */
    private static final Long TASK_ID = 9_000L;

    @Mock
    private TestCaseMapper testCaseMapper;

    @Mock
    private CaseReviewMapper caseReviewMapper;

    @Mock
    private CaseReviewService caseReviewService;

    private CaseService service;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存（{@code TestCase} / {@code CaseReview}）。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        service = new CaseService(testCaseMapper, caseReviewMapper, caseReviewService);
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 列表：{@code steps} 解析成对象数组，评审历史恒为空。
     */
    @Test
    @DisplayName("列表：steps 解析成对象数组，且不带评审历史")
    void pageParsesStepsIntoObjectsAndSkipsHistory() {
        Page<TestCase> page = new Page<>(1, 20);
        page.setRecords(List.of(caseRow(CASE_ID, "DRAFT")));
        page.setTotal(1L);
        when(testCaseMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<TestCaseVO> result = service.page(TASK_ID, "DRAFT", "登录", new PageQuery());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getRecords()).hasSize(1);
        TestCaseVO vo = result.getRecords().get(0);
        assertThat(vo.steps()).hasSize(1);
        assertThat(vo.steps().get(0).no()).isEqualTo(1);
        assertThat(vo.steps().get(0).action()).isEqualTo("输入账号");
        assertThat(vo.steps().get(0).data()).isEqualTo("admin");
        assertThat(vo.reviews()).as("列表页不查历史").isEmpty();
        // 列表页不该多查一次台账
        verify(caseReviewMapper, never()).selectList(any());
    }

    /**
     * 列表：脏 {@code steps} 降级成空数组，而不是让整页 500。
     */
    @Test
    @DisplayName("列表：steps 是脏 JSON 时降级成空数组（故障半径限制在一行）")
    void pageDegradesDirtyStepsToEmptyList() {
        TestCase dirty = caseRow(CASE_ID, "DRAFT");
        dirty.setSteps("{not-a-json-array");
        Page<TestCase> page = new Page<>(1, 20);
        page.setRecords(List.of(dirty));
        page.setTotal(1L);
        when(testCaseMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<TestCaseVO> result = service.page(TASK_ID, null, null, new PageQuery());

        assertThat(result.getRecords().get(0).steps()).isEmpty();
        assertThat(result.getRecords().get(0).title()).as("其它字段照常返回").isEqualTo("旧标题");
    }

    /**
     * 列表：{@code size} 走 {@code sizeOrDefault()}，5000 被截到 100。
     */
    @Test
    @DisplayName("列表：size=5000 被截到 100（必须走 sizeOrDefault）")
    void pageCapsPageSizeAtHundred() {
        Page<TestCase> page = new Page<>(1, 100);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(testCaseMapper.selectPage(any(), any())).thenReturn(page);

        PageQuery query = new PageQuery();
        query.setSize(5000);
        PageResult<TestCaseVO> result = service.page(TASK_ID, null, null, query);

        assertThat(result.getSize()).isEqualTo(PageQuery.MAX_SIZE);
    }

    /**
     * 列表：默认 created_at DESC + id DESC 兜底。
     *
     * <p>这条锁住一个很容易复发的坑：MP 的 {@code orderBy(condition, isAsc, column)}
     * 第二参是 <b>isAsc</b>，把 {@code PageQuery.isDesc()} 原样传进去会把默认排序翻成
     * ASC（最新用例排到最后），且不报错、只在肉眼比对列表时才看得出来。</p>
     */
    @Test
    @DisplayName("列表：默认 created_at DESC，且末尾补 id DESC 形成全序")
    void pageSortsByCreatedAtDescWithIdTieBreak() {
        Page<TestCase> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(testCaseMapper.selectPage(any(), any())).thenReturn(page);
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TestCase>>
                wrapper = (org.mockito.ArgumentCaptor) org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);

        service.page(TASK_ID, null, null, new PageQuery());

        verify(testCaseMapper).selectPage(any(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("created_at DESC").containsIgnoringCase("id DESC");
        assertThat(sql).doesNotContain("created_at ASC");
    }

    /**
     * 列表：状态是脏值 → {@code 40001}，且不查库。
     */
    @Test
    @DisplayName("列表：status 脏值返回 40001，不静默返回空列表")
    void pageRejectsUnknownStatus() {
        BizException ex = catchThrowableOfType(() -> service.page(TASK_ID, "PENDING", null, new PageQuery()),
                BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(ex.getMessage()).contains("DRAFT").contains("ACCEPTED");
        verify(testCaseMapper, never()).selectPage(any(), any());
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    /**
     * 详情：带评审历史，快照转成 JSON 节点。
     */
    @Test
    @DisplayName("详情：带 reviews 时间线，编辑快照返回成 JSON 对象而不是转义字符串")
    void getReturnsTimelineWithSnapshotNodes() {
        when(testCaseMapper.selectById(CASE_ID)).thenReturn(caseRow(CASE_ID, "EDITED"));
        when(caseReviewMapper.selectList(any())).thenReturn(List.of(
                reviewRow(1L, "REJECT", "EXPECT_UNCLEAR", null, null),
                reviewRow(2L, "EDIT", null,
                        "{\"title\":\"旧标题\"}", "{\"title\":\"新标题\"}")));

        TestCaseVO vo = service.get(CASE_ID);

        assertThat(vo.id()).isEqualTo(CASE_ID);
        assertThat(vo.steps()).hasSize(1);
        assertThat(vo.reviews()).hasSize(2);
        assertThat(vo.reviews().get(0).action()).isEqualTo("REJECT");
        assertThat(vo.reviews().get(0).rejectReasonCode()).isEqualTo("EXPECT_UNCLEAR");
        assertThat(vo.reviews().get(0).oldContent()).as("驳回没有快照").isNull();
        assertThat(vo.reviews().get(1).action()).isEqualTo("EDIT");
        assertThat(vo.reviews().get(1).oldContent().get("title").asText()).isEqualTo("旧标题");
        assertThat(vo.reviews().get(1).newContent().get("title").asText()).isEqualTo("新标题");
        assertThat(vo.reviews().get(1).createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 2));
    }

    /**
     * 详情：用例不存在 → 40401。
     */
    @Test
    @DisplayName("详情：用例不存在返回 40401，不查台账")
    void getMissingCaseFailsWithNotFound() {
        when(testCaseMapper.selectById(CASE_ID)).thenReturn(null);

        BizException ex = catchThrowableOfType(() -> service.get(CASE_ID), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_NOT_FOUND);
        verify(caseReviewMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 写操作转发
    // ------------------------------------------------------------------

    /**
     * 编辑：委托给写服务，然后<b>回读库里</b>的状态（不是内存里拼的）。
     */
    @Test
    @DisplayName("编辑：委托写服务后回读详情（时间戳来自库里，不是内存）")
    void updateDelegatesToReviewServiceThenReadsBack() {
        when(caseReviewService.edit(eq(CASE_ID), any())).thenReturn(new CaseReview());
        when(testCaseMapper.selectById(CASE_ID)).thenReturn(caseRow(CASE_ID, "EDITED"));
        when(caseReviewMapper.selectList(any())).thenReturn(List.of(
                reviewRow(3L, "EDIT", null, "{}", "{}")));

        TestCaseVO vo = service.update(CASE_ID,
                new CaseUpdateReq("新标题", null, null, null, null, null));

        verify(caseReviewService).edit(eq(CASE_ID), any());
        assertThat(vo.status()).isEqualTo("EDITED");
        assertThat(vo.updatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        assertThat(vo.reviews()).hasSize(1);
    }

    /**
     * 单条评审：委托后回读，返回值里带上刚落库的那行台账。
     */
    @Test
    @DisplayName("单条评审：委托后回读，返回值含刚落库的评审行")
    void reviewDelegatesThenReadsBack() {
        when(caseReviewService.apply(eq(CASE_ID), any())).thenReturn(new CaseReview());
        when(testCaseMapper.selectById(CASE_ID)).thenReturn(caseRow(CASE_ID, "REJECTED"));
        when(caseReviewMapper.selectList(any())).thenReturn(List.of(
                reviewRow(4L, "REJECT", "EXPECT_WRONG", null, null)));

        TestCaseVO vo = service.review(CASE_ID, new ReviewReq("REJECT", "EXPECT_WRONG", "预期写错了"));

        verify(caseReviewService).apply(eq(CASE_ID), any());
        assertThat(vo.status()).isEqualTo("REJECTED");
        assertThat(vo.reviews()).hasSize(1);
        assertThat(vo.reviews().get(0).reasonText()).isEqualTo("预期写错了");
    }

    /**
     * 批量评审：直接转发，不做二次聚合。
     */
    @Test
    @DisplayName("批量评审：直接转发写服务的结果")
    void batchReviewDelegates() {
        BatchReviewResult expected = new BatchReviewResult(2,
                List.of(new BatchReviewResult.Skipped(9L, "已评审（当前状态 ACCEPTED）")));
        when(caseReviewService.batchReview(any())).thenReturn(expected);

        BatchReviewResult actual = service.batchReview(
                new BatchReviewReq(List.of(1L, 2L, 9L), "ACCEPT", null, null));

        assertThat(actual).isSameAs(expected);
        verify(caseReviewService).batchReview(any());
        verify(testCaseMapper, never()).selectById(any());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 构造一条用例行。
     *
     * @param id     主键
     * @param status 状态
     * @return 实体
     */
    private static TestCase caseRow(Long id, String status) {
        TestCase entity = new TestCase();
        entity.setId(id);
        entity.setTaskId(TASK_ID);
        entity.setTaskItemId(90_000L);
        entity.setTestPointId(900_000L);
        entity.setDocId(9_000_000L);
        entity.setSeqNo(1);
        entity.setTitle("旧标题");
        entity.setPrecondition("已登录");
        entity.setSteps("[{\"no\":1,\"action\":\"输入账号\",\"data\":\"admin\"}]");
        entity.setExpected("返回 200");
        entity.setPriority("P1");
        entity.setCaseType("FUNCTIONAL");
        entity.setStatus(status);
        entity.setConfidence(new BigDecimal("0.500"));
        entity.setPromptVersion("case_generate.v1");
        entity.setModel("deepseek-chat");
        entity.setContentHash("content-hash-1");
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
        return entity;
    }

    /**
     * 构造一条台账行（{@code createdAt} 固定，便于断言顺序）。
     *
     * @param id      主键
     * @param action  动作
     * @param code    原因码（可空）
     * @param oldJson 旧快照（可空）
     * @param newJson 新快照（可空）
     * @return 台账实体
     */
    private static CaseReview reviewRow(Long id, String action, String code,
                                        String oldJson, String newJson) {
        CaseReview row = new CaseReview();
        row.setId(id);
        row.setCaseId(CASE_ID);
        row.setAction(action);
        row.setRejectReasonCode(code);
        row.setReasonText(action.equals("EDIT") ? "" : "预期写错了");
        row.setOldContent(oldJson);
        row.setNewContent(newJson);
        row.setReviewerId(42L);
        row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0, id.intValue()));
        return row;
    }
}
