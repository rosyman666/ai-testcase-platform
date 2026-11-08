package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.doc.domain.DocSource;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testpoint.dto.ExtractUnit;
import com.aittest.testpoint.dto.TestPointExtractResult;
import com.aittest.testpoint.service.ExtractPlanner;
import com.aittest.testpoint.service.TestPointExtractService;
import com.aittest.testpoint.service.TestPointExtractService.UnitResult;
import com.aittest.testpoint.service.TestPointService;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T05 ↔ T06 接缝的契约锁（D-26：去重单元级、取代文档级）。
 *
 * <p>三条重点：① 每条 item 成功后的去重键必须落进 {@code kept_hashes}（异步下收尾只能从库里汇总）；
 * ② {@code supersedeMissing} 每次任务收尾<b>恰好调用一次</b>；
 * ③ 失败单元必须用 {@code unit_ref}（= {@code source_ref}）反查原有测试点并入 kept——
 * 异步 item 不存 operation_id / section_id 两个 FK，用它们反查必然查空。</p>
 *
 * <p><b>B1 补充</b>：取消的任务绝不能跑文档级取代（没有"本轮产出全集"这个依据），
 * 且被 {@code SKIPPED} 的单元也要保住原有的点。</p>
 *
 * <p><b>台账归因</b>：异步 execute 必须走 {@code extractUnit(unit, taskId, itemId)} 三参重载，
 * 不能退回同步用的单参重载（否则 {@code llm_call_log} 的 task_id/task_item_id 恒 NULL）。
 * {@code attemptNo} 恒 1 是已知偏差（留 T07，见 TestPointExtractHandler 类注释），本批不碰。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class TestPointExtractHandlerTest {

    private static final Long DOC_ID = 9001L;

    private static final Long TASK_ID = 7100L;

    private static final String REF_OK = "operation:createUser";

    private static final String REF_FAILED = "operation:deleteUser";

    @Mock
    private ExtractPlanner extractPlanner;

    @Mock
    private TestPointExtractService extractService;

    @Mock
    private TestPointService testPointService;

    @Mock
    private DocSourceMapper docSourceMapper;

    @Mock
    private GenTaskItemMapper genTaskItemMapper;

    private TestPointExtractHandler handler;

    @BeforeAll
    static void installLambdaCache() {
        // execute() 写 kept_hashes、onTaskFinalize() 查 item 列表都拼 lambda wrapper
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        handler = new TestPointExtractHandler(extractPlanner, extractService,
                testPointService, docSourceMapper, genTaskItemMapper);
    }

    private static GenTask newTask() {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo("T20261107-000001");
        t.setDocId(DOC_ID);
        t.setScene(TaskScene.TESTPOINT_EXTRACT.code());
        return t;
    }

    private static GenTaskItem newItem(long id, String status, String unitRef, String keptHashes) {
        GenTaskItem item = new GenTaskItem();
        item.setId(id);
        item.setTaskId(TASK_ID);
        item.setStatus(status);
        item.setUnitRef(unitRef);
        item.setKeptHashes(keptHashes);
        return item;
    }

    private static ExtractUnit unit(String ref) {
        return new ExtractUnit(DOC_ID, null, 3L, ref, "订单系统", "POST /users 创建用户", 10);
    }

    private static TestPointExtractResult okResult(int inserted) {
        return new TestPointExtractResult(REF_OK, true, inserted, inserted, false, false, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<GenTaskItem>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static List<Object> setValues(Wrapper<?> wrapper) {
        if (wrapper instanceof LambdaUpdateWrapper<?> lambda) {
            lambda.getSqlSet();
        }
        AbstractWrapper<?, ?, ?> base = (AbstractWrapper<?, ?, ?>) wrapper;
        return new ArrayList<>(base.getParamNameValuePairs().values());
    }

    /** 让 findUnit 能按 sourceRef 命中给定的单元。 */
    private void wirePlan(String ref) {
        DocSource doc = new DocSource();
        doc.setId(DOC_ID);
        when(docSourceMapper.selectById(DOC_ID)).thenReturn(doc);
        when(extractPlanner.plan(doc)).thenReturn(List.of(unit(ref)));
    }

    // ------------------------------------------------------------------
    // execute：kept_hashes 落库 + 任务上下文透传
    // ------------------------------------------------------------------

    @Test
    @DisplayName("item 成功：UnitResult.keptHashes 逗号拼接后写入 gen_task_item.kept_hashes")
    void executePersistsKeptHashes() {
        wirePlan(REF_OK);
        GenTaskItem item = newItem(7201L, "RUNNING", REF_OK, null);
        Set<String> hashes = new LinkedHashSet<>(List.of("h1", "h2", "h3"));
        when(extractService.extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7201L)))
                .thenReturn(new UnitResult(okResult(2), hashes));

        ItemOutcome outcome = handler.execute(newTask(), item);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.produced()).isEqualTo(2);
        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("h1,h2,h3");
    }

    @Test
    @DisplayName("抽取失败：仍写 kept_hashes（保全原有点），并收敛为 ItemOutcome.failed")
    void executeFailureStillPersistsKeptHashes() {
        wirePlan(REF_OK);
        GenTaskItem item = newItem(7202L, "RUNNING", REF_OK, null);
        TestPointExtractResult failed = TestPointExtractResult.failed(REF_OK, "模型超时");
        when(extractService.extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7202L)))
                .thenReturn(new UnitResult(failed, new LinkedHashSet<>(List.of("old-point"))));

        ItemOutcome outcome = handler.execute(newTask(), item);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("E_LLM_UPSTREAM");
        assertThat(outcome.errorMsg()).isEqualTo("模型超时");
        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("old-point");
    }

    @Test
    @DisplayName("台账归因：execute 走三参重载，把 taskId/itemId 原样透传，不退回同步单参重载")
    void executePassesTaskContextForCostLedger() {
        wirePlan(REF_OK);
        GenTaskItem item = newItem(7210L, "RUNNING", REF_OK, null);
        when(extractService.extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7210L)))
                .thenReturn(new UnitResult(okResult(1), new LinkedHashSet<>()));

        ItemOutcome outcome = handler.execute(newTask(), item);

        assertThat(outcome.success()).isTrue();
        verify(extractService).extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7210L));
        // 单参重载是 T05 同步路径专用；异步链路退回它就会让 llm_call_log 两列恒 NULL
        verify(extractService, never()).extractUnit(any(ExtractUnit.class));
    }

    @Test
    @DisplayName("[已知偏差] 重试 item 的 attempts 不参与台账归因：仍只透传 taskId/itemId（attemptNo 恒 1，留 T07）")
    void retryItemAttemptsDoNotLeakIntoCostLedgerContext() {
        wirePlan(REF_OK);
        GenTaskItem item = newItem(7211L, "RUNNING", REF_OK, null);
        item.setAttempts(2);
        when(extractService.extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7211L)))
                .thenReturn(new UnitResult(okResult(1), new LinkedHashSet<>()));

        ItemOutcome outcome = handler.execute(newTask(), item);

        assertThat(outcome.success()).isTrue();
        // 本批的 handler 契约只有两个 id：attempts 既不被当作 attemptNo 透传，
        // 也不改变这两个 id 的值。真实重试次数归因留 T07，这里锁住"不越界"。
        verify(extractService).extractUnit(eq(unit(REF_OK)), eq(TASK_ID), eq(7211L));
        verify(extractService, never()).extractUnit(any(ExtractUnit.class));
    }

    @Test
    @DisplayName("按 sourceRef 找不到单元 → 40901，且不调用抽取服务")
    void missingUnitThrowsStateIllegal() {
        wirePlan("operation:another");

        GenTaskItem item = newItem(7203L, "RUNNING", REF_OK, null);
        BizException ex = assertThrows(BizException.class, () -> handler.execute(newTask(), item));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_STATE_ILLEGAL);
        assertThat(ex.getMessage()).contains("找不到待分析单元", REF_OK);
        verifyNoInteractions(extractService);
    }

    @Test
    @DisplayName("关联文档不存在 → 40401")
    void missingDocThrowsNotFound() {
        when(docSourceMapper.selectById(DOC_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> handler.execute(newTask(), newItem(7204L, "RUNNING", REF_OK, null)));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_NOT_FOUND);
        verifyNoInteractions(extractPlanner, extractService);
    }

    // ------------------------------------------------------------------
    // onTaskFinalize：文档级取代恰好一次
    // ------------------------------------------------------------------

    @Test
    @DisplayName("收尾：汇总 SUCCESS 的 kept_hashes ∪ FAILED 的原有点，supersedeMissing 恰好一次")
    void finalizeAggregatesKeptAndSupersedesOnce() {
        List<GenTaskItem> items = List.of(
                newItem(1L, "SUCCESS", REF_OK, "a,b"),
                newItem(2L, "SUCCESS", "operation:list", "c,"),
                newItem(3L, "FAILED", REF_FAILED, null),
                newItem(4L, "PENDING", "operation:ignored", "should-not-count"));
        when(genTaskItemMapper.selectList(any())).thenReturn(items);
        when(testPointService.keptHashesOfUnitRef(eq(DOC_ID), eq(REF_FAILED)))
                .thenReturn(Set.of("old-1", "old-2"));

        handler.onTaskFinalize(newTask(), TaskStatus.SUCCESS);

        ArgumentCaptor<Set<String>> kept = setCaptor();
        verify(testPointService, times(1)).supersedeMissing(eq(DOC_ID), kept.capture());
        assertThat(kept.getValue()).containsExactlyInAnyOrder("a", "b", "c", "old-1", "old-2");
        assertThat(kept.getValue()).doesNotContain("should-not-count");
        // 失败单元按 sourceRef 反查，而不是按两个 FK
        verify(testPointService).keptHashesOfUnitRef(DOC_ID, REF_FAILED);
        verify(testPointService, never()).keptHashesOfUnit(any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Set<String>> setCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Set.class);
    }

    @Test
    @DisplayName("收尾：全是 SUCCESS 时也只取代一次，且不去反查失败单元")
    void finalizeWithOnlySuccessItemsStillSupersedesOnce() {
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(
                newItem(1L, "SUCCESS", REF_OK, "x"),
                newItem(2L, "SUCCESS", "operation:list", "y")));

        handler.onTaskFinalize(newTask(), TaskStatus.SUCCESS);

        verify(testPointService, times(1)).supersedeMissing(eq(DOC_ID), any());
        verify(testPointService, never()).keptHashesOfUnitRef(any(), any());
        verify(testPointService, never()).keptHashesOfUnit(any(), any(), any());
    }

    @Test
    @DisplayName("收尾：item 的 kept_hashes 为空/空白时不产生空串 kept")
    void blankKeptHashesAreIgnored() {
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(
                newItem(1L, "SUCCESS", REF_OK, ""),
                newItem(2L, "SUCCESS", "operation:list", "  ,  "),
                newItem(3L, "SUCCESS", "operation:real", "real-hash")));

        handler.onTaskFinalize(newTask(), TaskStatus.SUCCESS);

        ArgumentCaptor<Set<String>> kept = setCaptor();
        verify(testPointService).supersedeMissing(eq(DOC_ID), kept.capture());
        assertThat(kept.getValue()).containsExactly("real-hash");
    }

    // ------------------------------------------------------------------
    // B1：取消 / 跳过单元绝不能误取代
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B1② 负向：终态 CANCELED 直接返回，连 item 列表都不查，绝不跑 supersedeMissing")
    void canceledTaskNeverSupersedes() {
        handler.onTaskFinalize(newTask(), TaskStatus.CANCELED);

        verify(genTaskItemMapper, never()).selectList(any());
        verify(testPointService, never()).supersedeMissing(any(), any());
        verify(testPointService, never()).keptHashesOfUnitRef(any(), any());
    }

    @Test
    @DisplayName("B1② 第二道防线：task.status 已是 CANCELED 时，即便传入 SUCCESS 也拒绝取代")
    void taskStatusCanceledNeverSupersedesEvenWithSuccessTerminal() {
        GenTask canceled = newTask();
        canceled.setStatus(TaskStatus.CANCELED.code());

        handler.onTaskFinalize(canceled, TaskStatus.SUCCESS);

        verify(genTaskItemMapper, never()).selectList(any());
        verify(testPointService, never()).supersedeMissing(any(), any());
        verify(testPointService, never()).keptHashesOfUnitRef(any(), any());
    }

    @Test
    @DisplayName("B1③ 负向：SKIPPED 单元按 unitRef 反查原有点并入 kept，不被误判为消失")
    void skippedUnitsKeepTheirOriginalPoints() {
        String refSkipped = "operation:skippedUnit";
        when(genTaskItemMapper.selectList(any())).thenReturn(List.of(
                newItem(1L, "SUCCESS", REF_OK, "new-hash"),
                newItem(2L, "SKIPPED", refSkipped, null),
                newItem(3L, "FAILED", REF_FAILED, null)));
        when(testPointService.keptHashesOfUnitRef(eq(DOC_ID), eq(refSkipped)))
                .thenReturn(Set.of("skipped-old"));
        when(testPointService.keptHashesOfUnitRef(eq(DOC_ID), eq(REF_FAILED)))
                .thenReturn(Set.of("failed-old"));

        handler.onTaskFinalize(newTask(), TaskStatus.SUCCESS);

        ArgumentCaptor<Set<String>> kept = setCaptor();
        verify(testPointService, times(1)).supersedeMissing(eq(DOC_ID), kept.capture());
        assertThat(kept.getValue())
                .containsExactlyInAnyOrder("new-hash", "skipped-old", "failed-old");
        verify(testPointService).keptHashesOfUnitRef(DOC_ID, refSkipped);
        verify(testPointService).keptHashesOfUnitRef(DOC_ID, REF_FAILED);
    }
}
