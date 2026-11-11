package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.IdGen;
import com.aittest.common.PageQuery;
import com.aittest.common.PageResult;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.dto.GenTaskItemVO;
import com.aittest.generation.dto.GenTaskVO;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testpoint.mapper.TestPointMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 任务列表与逐 item 明细的契约测试（T05 补漏，T07-3 验收⑥ 的数据源）。
 *
 * <p><b>本类要挡住的具体事故</b>：</p>
 * <ul>
 *   <li>筛选参数传了脏值被静默忽略 → 用户以为筛了"抽点任务"，实际看到的是全量列表；</li>
 *   <li>列表只按 {@code created_at DESC} 排 → 同毫秒建的两个任务在翻页时漏行/重复；</li>
 *   <li>{@code /items} 不先查任务 → 不存在的 taskId 返回空数组，前端分不清"任务没了"
 *       和"还没有 item"这两个完全不同的状态；</li>
 *   <li>{@code /items} 按 {@code unitRef} 字典序排 → {@code section:10} 跑到
 *       {@code section:2} 前面，表格行序在不同任务间乱跳；</li>
 *   <li>item 级 {@code errorCode} 被任务级（恒 {@code E_INTERNAL}）覆盖 →
 *       D-44 修好的真实失败原因在详情页看不见。</li>
 * </ul>
 *
 * <p><b>为什么用 mock</b>：被测的是"发了什么查询条件、按什么顺序排、字段怎么映射"，
 * 不是 MySQL 的 {@code LIMIT/OFFSET} 行为。连库会把时区、分页插件、Flyway 一起卷进来，
 * 反而看不清契约。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class GenTaskQueryTest {

    /** 任务主键。 */
    private static final Long TASK_ID = 7_100_001L;

    /** 文档主键。 */
    private static final Long DOC_ID = 9_100_001L;

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private GenTaskItemMapper genTaskItemMapper;

    @Mock
    private TestPointMapper testPointMapper;

    @Mock
    private IdGen idGen;

    @Mock
    private ProgressPublisher progressPublisher;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    @InjectMocks
    private GenTaskService service;

    /**
     * 安装 MyBatis-Plus 的 lambda 列缓存（{@code GenTask} / {@code GenTaskItem}）。
     */
    @BeforeAll
    static void installLambdaCache() {
        MybatisPlusLambdaCache.install();
    }

    // ------------------------------------------------------------------
    // GET /api/tasks：分页、筛选、排序
    // ------------------------------------------------------------------

    /**
     * 列表：过滤条件与 VO 字段映射。
     */
    @Test
    @DisplayName("列表：scene/status/docId 过滤生效，实体字段完整映射成 GenTaskVO")
    void pageFiltersAndMapsVoFields() {
        Page<GenTask> page = new Page<>(1, 20);
        page.setRecords(List.of(task("CASE_GENERATE", "PARTIAL")));
        page.setTotal(3L);
        when(genTaskMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<GenTaskVO> result = service.page("CASE_GENERATE", "PARTIAL", DOC_ID, new PageQuery());

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getRecords()).hasSize(1);
        GenTaskVO vo = result.getRecords().get(0);
        assertThat(vo.taskId()).isEqualTo(String.valueOf(TASK_ID));
        assertThat(vo.taskNo()).isEqualTo("T20261108-000001");
        assertThat(vo.scene()).isEqualTo("CASE_GENERATE");
        assertThat(vo.status()).isEqualTo("PARTIAL");
        assertThat(vo.progress()).isEqualTo(60);
        assertThat(vo.done()).isEqualTo(6);
        assertThat(vo.total()).isEqualTo(10);
        assertThat(vo.success()).isEqualTo(5);
        assertThat(vo.failed()).isEqualTo(1);
        assertThat(vo.produced()).as("produced 读 gen_task.produced_items，不是 accepted_cases").isEqualTo(14);
        assertThat(vo.errorCode()).isEqualTo("E_INTERNAL");
        assertThat(vo.createdAt()).isEqualTo(Instant.parse("2026-11-08T08:00:00Z"));
    }

    /**
     * 列表：默认 created_at DESC + id DESC 兜底。
     */
    @Test
    @DisplayName("列表：默认 created_at DESC，且末尾补 id DESC 形成全序")
    void pageSortsByCreatedAtDescWithIdTieBreak() {
        Page<GenTask> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(genTaskMapper.selectPage(any(), any())).thenReturn(page);
        ArgumentCaptor<LambdaQueryWrapper<GenTask>> wrapper = taskWrapperCaptor();

        service.page(null, null, null, new PageQuery());

        verify(genTaskMapper).selectPage(any(), wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("created_at DESC").containsIgnoringCase("id DESC");
    }

    /**
     * 列表：脏 scene / 脏 status 一律 40001，且不查库。
     */
    @Test
    @DisplayName("列表：scene/status 脏值返回 40001，不静默当成无筛选")
    void pageRejectsUnknownSceneAndStatus() {
        BizException badScene = catchThrowableOfType(
                () -> service.page("NOT_A_SCENE", null, null, new PageQuery()), BizException.class);
        assertThat(badScene.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(badScene.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(badScene.getMessage()).contains("任务场景").contains("CASE_GENERATE");

        BizException badStatus = catchThrowableOfType(
                () -> service.page(null, "DONE", null, new PageQuery()), BizException.class);
        assertThat(badStatus.getErrorCode()).isEqualTo(ErrorCode.E_PARAM_INVALID);
        assertThat(badStatus.getMessage()).contains("任务状态").contains("PENDING").contains("CANCELED");

        verify(genTaskMapper, never()).selectPage(any(), any());
    }

    /**
     * 列表：空白串 = 不筛（前端清空下拉框常传空串）。
     */
    @Test
    @DisplayName("列表：scene/status 空白串视为不筛，正常查询")
    void pageTreatsBlankFiltersAsNoFilter() {
        Page<GenTask> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(genTaskMapper.selectPage(any(), any())).thenReturn(page);

        service.page("   ", "", null, new PageQuery());

        verify(genTaskMapper).selectPage(any(), any());
    }

    /**
     * 列表：size 走 sizeOrDefault()，5000 截到 100。
     */
    @Test
    @DisplayName("列表：size=5000 被截到 100（必须走 sizeOrDefault）")
    void pageCapsSizeAtHundred() {
        Page<GenTask> page = new Page<>(1, 100);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(genTaskMapper.selectPage(any(), any())).thenReturn(page);
        PageQuery query = new PageQuery();
        query.setSize(5000);

        PageResult<GenTaskVO> result = service.page(null, null, null, query);

        assertThat(result.getSize()).isEqualTo(PageQuery.MAX_SIZE);
    }

    // ------------------------------------------------------------------
    // GET /api/tasks/{id}/items：明细
    // ------------------------------------------------------------------

    /**
     * 明细：按 id ASC 查，不按 unitRef 排。
     */
    @Test
    @DisplayName("明细：按 id ASC 排（插入顺序），不按 unitRef 字典序")
    void itemsAreQueriedByIdAsc() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task("TESTPOINT_EXTRACT", "SUCCESS"));
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(
                item(101L, "section:2", "SUCCESS"),
                item(102L, "section:10", "FAILED")));
        ArgumentCaptor<LambdaQueryWrapper<GenTaskItem>> wrapper = itemWrapperCaptor();

        List<GenTaskItemVO> items = service.listItems(TASK_ID);

        assertThat(items).extracting(GenTaskItemVO::unitRef)
                .as("section:2 必须排在 section:10 前面：字典序会把 10 排到 2 前")
                .containsExactly("section:2", "section:10");
        verify(genTaskItemMapper).selectList(wrapper.capture());
        String sql = wrapper.getValue().getSqlSegment();
        assertThat(sql).containsIgnoringCase("order by id asc");
        assertThat(sql).containsIgnoringCase("task_id");
    }

    /**
     * 明细：字段完整映射，尤其是 item 级 errorCode 与 degraded。
     */
    @Test
    @DisplayName("明细：14 个字段全部映射，item 级 errorCode 不被任务级 E_INTERNAL 覆盖")
    void itemsMapEveryField() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task("CASE_GENERATE", "PARTIAL"));
        GenTaskItem failed = item(201L, "testpoint:9001", "FAILED");
        failed.setTestPointId(9001L);
        failed.setAttempts(3);
        failed.setCaseCount(0);
        failed.setDegraded(0);
        failed.setPromptTokens(1200);
        failed.setCompletionTokens(340);
        failed.setCostMicro(8800L);
        failed.setLatencyMs(4210);
        failed.setErrorCode("E_LLM_UPSTREAM");
        failed.setErrorMsg("上游超时");
        failed.setStartedAt(LocalDateTime.of(2026, 11, 8, 8, 0, 1));
        failed.setFinishedAt(LocalDateTime.of(2026, 11, 8, 8, 0, 5));
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(failed));

        List<GenTaskItemVO> items = service.listItems(TASK_ID);

        assertThat(items).hasSize(1);
        GenTaskItemVO vo = items.get(0);
        assertThat(vo.id()).isEqualTo("201");
        assertThat(vo.testPointId()).isEqualTo("9001");
        assertThat(vo.unitRef()).isEqualTo("testpoint:9001");
        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.attempts()).isEqualTo(3);
        assertThat(vo.caseCount()).isZero();
        assertThat(vo.degraded()).isFalse();
        assertThat(vo.promptTokens()).isEqualTo(1200);
        assertThat(vo.completionTokens()).isEqualTo(340);
        assertThat(vo.costMicro()).isEqualTo(8800L);
        assertThat(vo.latencyMs()).isEqualTo(4210);
        assertThat(vo.errorCode()).as("D-44 修好的 item 级错误码，任务级仍是 E_INTERNAL")
                .isEqualTo("E_LLM_UPSTREAM");
        assertThat(vo.errorMsg()).isEqualTo("上游超时");
        assertThat(vo.startedAt()).isEqualTo(Instant.parse("2026-11-08T08:00:01Z"));
        assertThat(vo.finishedAt()).isEqualTo(Instant.parse("2026-11-08T08:00:05Z"));
    }

    /**
     * 明细：degraded=1 → true；整型列 null → 0，不让前端渲染出 NaN。
     */
    @Test
    @DisplayName("明细：degraded=1 映射成 true，null 计数列降级成 0")
    void itemsNormalizeDegradedAndNullNumbers() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(task("CASE_GENERATE", "SUCCESS"));
        GenTaskItem lenient = new GenTaskItem();
        lenient.setId(301L);
        lenient.setTaskId(TASK_ID);
        lenient.setUnitRef(null);
        lenient.setStatus("SUCCESS");
        lenient.setDegraded(1);
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(lenient));

        GenTaskItemVO vo = service.listItems(TASK_ID).get(0);

        assertThat(vo.degraded()).isTrue();
        assertThat(vo.attempts()).isZero();
        assertThat(vo.caseCount()).isZero();
        assertThat(vo.promptTokens()).isZero();
        assertThat(vo.completionTokens()).isZero();
        assertThat(vo.costMicro()).isZero();
        assertThat(vo.latencyMs()).isZero();
        assertThat(vo.testPointId()).isNull();
        assertThat(vo.unitRef()).isNull();
        assertThat(vo.startedAt()).isNull();
        assertThat(vo.finishedAt()).isNull();
    }

    /**
     * 明细：任务不存在 → 40401，且不查 item 表。
     */
    @Test
    @DisplayName("明细：任务不存在返回 40401，不返回空数组")
    void itemsOfMissingTaskFailWithNotFound() {
        when(genTaskMapper.selectById(TASK_ID)).thenReturn(null);

        BizException ex = catchThrowableOfType(() -> service.listItems(TASK_ID), BizException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_NOT_FOUND);
        assertThat(ex.getErrorCode().getHttpStatus().value()).isEqualTo(404);
        verify(genTaskItemMapper, never()).selectList(any());
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /**
     * 构造一条任务行（所有 {@code GenTaskVO} 读到的列都填上）。
     *
     * @param scene  场景
     * @param status 状态
     * @return 实体
     */
    private static GenTask task(String scene, String status) {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo("T20261108-000001");
        t.setScene(scene);
        t.setDocId(DOC_ID);
        t.setProvider("mock");
        t.setModel("deepseek-chat");
        t.setStatus(status);
        t.setTotalItems(10);
        t.setDoneItems(6);
        t.setSuccessItems(5);
        t.setFailedItems(1);
        t.setProducedItems(14);
        t.setAcceptedCases(0);
        t.setProgress(60);
        t.setErrorCode("E_INTERNAL");
        t.setErrorMsg("有 1 条子项失败");
        t.setCreatedAt(LocalDateTime.of(2026, 11, 8, 8, 0, 0));
        return t;
    }

    /**
     * 构造一条子项行（只填本测试关心的最小集合）。
     *
     * @param id      主键
     * @param unitRef 单元定位
     * @param status  状态
     * @return 实体
     */
    private static GenTaskItem item(long id, String unitRef, String status) {
        GenTaskItem row = new GenTaskItem();
        row.setId(id);
        row.setTaskId(TASK_ID);
        row.setUnitRef(unitRef);
        row.setStatus(status);
        return row;
    }

    /** 类型安全的 wrapper 捕获器（泛型擦除，集中抑制一次）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<GenTask>> taskWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    /** 类型安全的 wrapper 捕获器（泛型擦除，集中抑制一次）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<GenTaskItem>> itemWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
