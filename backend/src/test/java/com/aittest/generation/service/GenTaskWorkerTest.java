package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.aicore.LlmException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 任务子项执行与收尾的契约锁（T06）。
 *
 * <p>四条重点：① 任务级"无错误"必须是空串而不是 null；② {@code markTerminal}
 * 返回 0 表示没抢到收尾权，此时不能重复推 finish、更不能调 {@code onTaskFinalize}
 * （B1：取消竞态下带残缺 kept 的文档级取代会误杀整篇文档的 ACTIVE 测试点）；
 * ③ 失败走"后移 next_run_at"退避，而不是 sleep，且到达 3 次后必须置 FAILED；
 * ④ <b>所有 item 终态分支</b>（SUCCESS/FAILED/SKIPPED）都要能触发任务收尾判定（B3）。</p>
 *
 * <p>{@code maybeFinalize} 是 private，这里统一通过 {@code run()} 的成功路径驱动——
 * 不反射、不为测试改可见性。"item 全部收尾"由 {@code countByStatus} 的 mock 输入表达。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class GenTaskWorkerTest {

    private static final Long TASK_ID = 7100L;

    private static final Long ITEM_ID = 7200L;

    private static final Long DOC_ID = 9001L;

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private GenTaskItemMapper genTaskItemMapper;

    @Mock
    private GenTaskService genTaskService;

    @Mock
    private ProgressPublisher progressPublisher;

    @Mock
    private TaskItemHandler handler;

    private GenTaskWorker worker;

    @BeforeAll
    static void installLambdaCache() {
        // completeItem/failPermanently/skipQuietly 都拼 LambdaUpdateWrapper
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        // scene() 在构造函数里被读取，每个用例都会用到，放这里是安全的
        when(handler.scene()).thenReturn(TaskScene.TESTPOINT_EXTRACT);
        worker = new GenTaskWorker(genTaskMapper, genTaskItemMapper, genTaskService,
                progressPublisher, List.of(handler));

        // 个别用例会覆盖这两个 stub（终态任务 / attempts=2），因此用 lenient 避免
        // "已 stub 但未使用" 的 UnnecessaryStubbingException 掩盖真正的断言失败
        lenient().when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(0));
        lenient().when(genTaskService.require(TASK_ID))
                .thenReturn(newTask(TaskStatus.RUNNING.name()));
        // B1④：所有 item 终态 update 都带 status='RUNNING' 守卫并检查影响行数。
        // 默认让 update 命中 1 行；"已被 SKIPPED" 的负向用例会覆盖成 0。
        lenient().when(genTaskItemMapper.update(ArgumentMatchers.<GenTaskItem>isNull(),
                ArgumentMatchers.<Wrapper<GenTaskItem>>any())).thenReturn(1);
    }

    private static GenTaskItem newItem(int attempts) {
        GenTaskItem item = new GenTaskItem();
        item.setId(ITEM_ID);
        item.setTaskId(TASK_ID);
        item.setUnitRef("operation:createUser");
        item.setStatus("RUNNING");
        item.setAttempts(attempts);
        return item;
    }

    private static GenTask newTask(String status) {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo("T20261107-000001");
        t.setStatus(status);
        t.setScene(TaskScene.TESTPOINT_EXTRACT.code());
        t.setDocId(DOC_ID);
        t.setTotalItems(3);
        return t;
    }

    private static Map<String, Object> row(String status, int cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("cnt", cnt);
        return m;
    }

    /** 取出 LambdaUpdateWrapper 里 set(...) 写入的参数值（避免拼 SQL 字符串做断言）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Wrapper<GenTaskItem>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private static List<Object> setValues(Wrapper<?> wrapper) {
        if (wrapper instanceof LambdaUpdateWrapper<?> lambda) {
            lambda.getSqlSet();
        }
        // 触发 WHERE 段渲染，让 .eq(GenTaskItem::getStatus, "RUNNING") 的条件值
        // 也注册进 paramNameValuePairs（否则守卫断言取不到值）。
        wrapper.getSqlSegment();
        AbstractWrapper<?, ?, ?> base = (AbstractWrapper<?, ?, ?>) wrapper;
        return new ArrayList<>(base.getParamNameValuePairs().values());
    }

    private void wireSuccessOutcome() throws Exception {
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenReturn(ItemOutcome.ok(2, 10, 20, 100L, 5));
    }

    private void wireAllItemsDone() {
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("SUCCESS", 3)));
    }

    // ------------------------------------------------------------------
    // maybeFinalize
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全部成功收尾：errorCode / errorMsg 都是空串而不是 null")
    void finalizeWithoutFailuresWritesEmptyStrings() throws Exception {
        wireSuccessOutcome();
        wireAllItemsDone();
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskMapper).markTerminal(eq(TASK_ID), status.capture(), code.capture(),
                msg.capture(), any(LocalDateTime.class));
        assertThat(status.getValue()).isEqualTo(TaskStatus.SUCCESS.code());
        assertThat(code.getValue()).isNotNull().isEmpty();
        assertThat(msg.getValue()).isNotNull().isEmpty();
        // B1：任务级收尾只在抢到收尾权后调用一次，并显式带终态
        verify(handler, times(1)).onTaskFinalize(any(GenTask.class), eq(TaskStatus.SUCCESS));
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.SUCCESS),
                eq(""), eq(""));
    }

    @Test
    @DisplayName("有失败 item：终态 PARTIAL，错误码 E_INTERNAL + 中文提示")
    void finalizeWithFailuresWritesChineseError() throws Exception {
        wireSuccessOutcome();
        when(genTaskItemMapper.countByStatus(TASK_ID))
                .thenReturn(List.of(row("SUCCESS", 2), row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskMapper).markTerminal(eq(TASK_ID), status.capture(), code.capture(),
                msg.capture(), any(LocalDateTime.class));
        assertThat(status.getValue()).isEqualTo(TaskStatus.PARTIAL.code());
        assertThat(code.getValue()).isEqualTo("E_INTERNAL");
        assertThat(msg.getValue()).contains("1", "条子项失败");
        verify(handler).onTaskFinalize(any(GenTask.class), eq(TaskStatus.PARTIAL));
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.PARTIAL),
                eq("E_INTERNAL"), eq("有 1 条子项失败"));
    }

    @Test
    @DisplayName("B1 核心：markTerminal 返回 0（取消抢先）→ 不推 finish、不刷进度、绝不调 onTaskFinalize")
    void losingFinalizeRaceDoesNotPublishFinishOrSupersede() throws Exception {
        wireSuccessOutcome();
        wireAllItemsDone();
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(0);

        worker.run(ITEM_ID);

        verify(progressPublisher, never()).finish(any(GenTask.class), any(TaskStatus.class),
                any(String.class), any(String.class));
        verify(progressPublisher, never()).refreshProgressField(any(GenTask.class));
        // 这一条是 B1 的数据破坏防线：没抢到收尾权就不能做文档级取代
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));
        verify(genTaskMapper, times(1)).markTerminal(eq(TASK_ID), any(String.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("B1④ 负向：cancel 已把 item 置 SKIPPED（update 命中 0 行）→ 不做记账也不收尾")
    void cancelInFlightSkippedItemDoesNotFinalize() throws Exception {
        wireSuccessOutcome();
        when(genTaskItemMapper.update(ArgumentMatchers.<GenTaskItem>isNull(),
                ArgumentMatchers.<Wrapper<GenTaskItem>>any())).thenReturn(0);

        worker.run(ITEM_ID);

        verify(progressPublisher, never()).onItemDone(any(), any(), any());
        verify(genTaskItemMapper, never()).countByStatus(any());
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));
        // item 终态 update 本身必须带 status='RUNNING' 守卫
        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("RUNNING", "SUCCESS");
    }

    @Test
    @DisplayName("仍有 PENDING / RUNNING 的 item 时不收尾")
    void doesNotFinalizeWhileItemsArePending() throws Exception {
        wireSuccessOutcome();
        when(genTaskItemMapper.countByStatus(TASK_ID))
                .thenReturn(List.of(row("SUCCESS", 2), row("RUNNING", 1)));

        worker.run(ITEM_ID);

        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        verify(progressPublisher, never()).finish(any(), any(), any(), any());
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));
    }

    // ------------------------------------------------------------------
    // 失败退避 / 永久失败
    // ------------------------------------------------------------------

    @Test
    @DisplayName("首次失败：退回 PENDING 并后移 next_run_at（不 sleep、不收尾）")
    void failureReschedulesWithBackoff() throws Exception {
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenReturn(ItemOutcome.failed("E_LLM_UPSTREAM", "模型超时", 10));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        worker.run(ITEM_ID);

        ArgumentCaptor<LocalDateTime> nextRun = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(genTaskItemMapper).rescheduleForRetry(eq(ITEM_ID), eq(1), nextRun.capture(),
                eq("E_LLM_UPSTREAM"), eq("模型超时"));
        assertThat(nextRun.getValue()).isAfter(before);
        assertThat(nextRun.getValue()).isBefore(before.plusSeconds(60));
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        verify(progressPublisher, never()).onItemDone(any(), any(), any());
    }

    @Test
    @DisplayName("I4 负向：NPE 无 message 时退避写入非空兜底（不是 null，不撞 NOT NULL 列）")
    void nullExceptionMessageFallsBackOnRetry() throws Exception {
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenThrow(new NullPointerException());

        worker.run(ITEM_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskItemMapper).rescheduleForRetry(eq(ITEM_ID), eq(1),
                any(LocalDateTime.class), code.capture(), msg.capture());
        assertThat(code.getValue()).isNotNull().isNotBlank().isEqualTo("E_INTERNAL");
        assertThat(msg.getValue()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("第 3 次失败：item 置 FAILED、attempts=3，不再安排重试")
    void thirdFailureIsPermanent() throws Exception {
        // 覆盖 @BeforeEach 的 item stub：本次 attempts 已经是 2
        when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(2));
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenReturn(ItemOutcome.failed("E_LLM_UPSTREAM", "模型超时", 10));
        // 全失败任务也必须能收尾（B3），但这里先锁 item 终态写入
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        verify(genTaskItemMapper, never()).rescheduleForRetry(any(), any(Integer.class),
                any(), any(), any());
        verify(progressPublisher).onItemDone(any(GenTask.class), any(GenTaskItem.class),
                any(ItemOutcome.class));

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        List<Object> values = setValues(wrapper.getValue());
        assertThat(values).contains("FAILED", 3, "E_LLM_UPSTREAM", "模型超时");
    }

    @Test
    @DisplayName("B3 负向：全失败任务的最后一条 item 收尾后，任务置 FAILED 并推 finish")
    void allFailedTaskStillFinalizes() throws Exception {
        when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(2));
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenReturn(ItemOutcome.failed("E_LLM_UPSTREAM", "模型超时", 10));
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq("E_INTERNAL"), eq("有 1 条子项失败"), any(LocalDateTime.class));
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.FAILED),
                eq("E_INTERNAL"), eq("有 1 条子项失败"));
        verify(handler).onTaskFinalize(any(GenTask.class), eq(TaskStatus.FAILED));
    }

    @Test
    @DisplayName("I4 负向：永久失败的 outcome 带 null 错误码/文案时落库兜底")
    void permanentFailureWithNullErrorsWritesFallbacks() throws Exception {
        when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(2));
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenReturn(ItemOutcome.failed(null, null, 10));
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("E_INTERNAL", "执行失败");
        assertThat(setValues(wrapper.getValue())).doesNotContain((Object) null);
    }

    @Test
    @DisplayName("成功 item：DB 里 status=SUCCESS、错误码/文案清空、attempts 落库（I8）")
    void successItemClearsErrorColumns() throws Exception {
        wireSuccessOutcome();
        when(genTaskItemMapper.countByStatus(TASK_ID))
                .thenReturn(List.of(row("SUCCESS", 1), row("PENDING", 1)));

        worker.run(ITEM_ID);

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        List<Object> values = setValues(wrapper.getValue());
        assertThat(values).contains("SUCCESS", "", 2, 1);
        assertThat(values).contains("RUNNING");
    }

    @Test
    @DisplayName("任务已终态时静默跳过 item：不执行处理器、不收尾，但计入 done（B3）")
    void terminalTaskSkipsItemQuietly() throws Exception {
        when(genTaskService.require(TASK_ID)).thenReturn(newTask(TaskStatus.CANCELED.name()));

        worker.run(ITEM_ID);

        verify(handler, never()).execute(any(), any());
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));
        verify(progressPublisher).onItemSkipped(any(GenTask.class), any(GenTaskItem.class));
        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("SKIPPED");
    }

    @Test
    @DisplayName("B3 负向：没有注册处理器的任务也能收尾（item 置 FAILED → 任务 FAILED → 推 finish）")
    void taskWithoutRegisteredHandlerStillFinalizes() throws Exception {
        // 把任务场景改成没有任何 handler 注册的值：executeAndSettle 走 failPermanently。
        // 旧实现只有成功分支才调 maybeFinalize，这类任务会永远停在 RUNNING、SSE 等不到终态。
        GenTask orphan = newTask(TaskStatus.RUNNING.name());
        orphan.setScene(TaskScene.CASE_GENERATE.code());
        when(genTaskService.require(TASK_ID)).thenReturn(orphan);
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        verify(handler, never()).execute(any(GenTask.class), any(GenTaskItem.class));
        verify(progressPublisher).onItemDone(any(GenTask.class), any(GenTaskItem.class),
                any(ItemOutcome.class));
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                any(String.class), any(String.class), any(LocalDateTime.class));
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.FAILED),
                any(String.class), any(String.class));
        // 没有 handler，自然也没有任务级收尾动作
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        assertThat(setValues(wrapper.getValue())).contains("FAILED", "E_INTERNAL");
    }

    @Test
    @DisplayName("B1 确定性竞态（CountDownLatch）：handler 执行中 cancel → 绝不触发文档级取代")
    void cancelWhileHandlerInFlightNeverSupersedes() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch cancelApplied = new CountDownLatch(1);
        AtomicReference<String> dbTaskStatus = new AtomicReference<>(TaskStatus.RUNNING.code());
        AtomicReference<Throwable> workerError = new AtomicReference<>();

        // 阻塞在 execute() 里，保证主线程能在「worker 已过 isFinalized 入口检查、
        // 还没 completeItem」这个真实竞态窗口内完成取消（e2e 里它是概率事件）。
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenAnswer(invocation -> {
                    handlerEntered.countDown();
                    if (!cancelApplied.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("cancel 未在 5s 内落库，竞态用例超时");
                    }
                    return ItemOutcome.ok(2, 10, 20, 100L, 5);
                });
        // markTerminal 的返回值模拟 DB 状态守卫：任务被 cancel 置 CANCELED 后必然返回 0。
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> TaskStatus.RUNNING.code().equals(dbTaskStatus.get()) ? 1 : 0);
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("SUCCESS", 3)));

        Thread workerThread = new Thread(() -> {
            try {
                worker.run(ITEM_ID);
            } catch (Throwable t) {
                workerError.set(t);
            }
        }, "t06-b1-inflight-worker");
        workerThread.start();

        assertThat(handlerEntered.await(5, TimeUnit.SECONDS))
                .as("worker 应已进入 handler.execute（即已过入口终态检查）").isTrue();
        // worker 手里仍是 RUNNING 的旧 task 快照；cancel 只改 DB 状态，不回改这份快照。
        dbTaskStatus.set(TaskStatus.CANCELED.code());
        cancelApplied.countDown();
        workerThread.join(5_000L);

        assertThat(workerThread.isAlive()).isFalse();
        assertThat(workerError.get()).isNull();
        // item 本身正常记账（修复后 in-flight 完成不被强行回退），收尾判定也确实跑到了
        verify(progressPublisher).onItemDone(any(GenTask.class), any(GenTaskItem.class),
                any(ItemOutcome.class));
        verify(genTaskMapper, times(1)).markTerminal(eq(TASK_ID), any(String.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
        // B1 核心：绝不能调 onTaskFinalize —— 抽点场景里它就是 supersedeMissing，
        // 带残缺 kept 会把整篇文档其余 ACTIVE 测试点误置 SUPERSEDED。
        // 纯单测没有真实 DB，这里以「handler 未被调用」等价守住「哨兵点仍 ACTIVE」。
        verify(handler, never()).onTaskFinalize(any(GenTask.class), any(TaskStatus.class));
        verify(progressPublisher, never()).finish(any(GenTask.class), any(TaskStatus.class),
                any(String.class), any(String.class));
        verify(progressPublisher, never()).refreshProgressField(any(GenTask.class));
    }

    @Test
    @DisplayName("I7 PENDING 收口：超时任务的 RUNNING+PENDING item 被回收清零后，public finalizeIfDone(Long) 收敛到终态")
    void expiredTaskConvergesAfterPendingAndRunningItemsRecycled() {
        // 回收前该任务同时带 PENDING(1 条，退避/未认领) 与 RUNNING(2 条，worker 崩溃)。
        // recycleExpiredTasks 的两条 SQL 把它们全置 FAILED；这里用回收后的
        // countByStatus 结果表达 "pending=0 且 running=0"，再走 dispatcher 真正调用的
        // public 入口 finalizeIfDone(Long)——否则回收器每 60s 空转、任务永不收敛。
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 3)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.finalizeIfDone(TASK_ID);

        // 3 条全失败 → FAILED 终态，且错误码/文案非空（NOT NULL 列）
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq("E_INTERNAL"), eq("有 3 条子项失败"), any(LocalDateTime.class));
        // B1 边界：抢到收尾权之后才允许做任务级动作、才推终态帧
        verify(handler).onTaskFinalize(any(GenTask.class), eq(TaskStatus.FAILED));
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.FAILED),
                eq("E_INTERNAL"), eq("有 3 条子项失败"));
        verify(progressPublisher).refreshProgressField(any(GenTask.class));
    }

    // ------------------------------------------------------------------
    // NEG-3：空 message 兜底（第 3 次尝试永久失败）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NEG-3 负向：第 3 次尝试抛无 message 的 NPE → item 置 FAILED，error_msg 走兜底非空、不含字面量 null")
    void thirdAttemptNullMessageNpeFallsBackOnPermanentFailure() throws Exception {
        // attempts 已到 2 → 本次是第 3 次，走 failPermanentlyWithOutcome
        when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(2));
        // JDK17 的 helpful NPE 只在有局部变量信息时生成 message；裸 new 出来 getMessage() 为 null
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenThrow(new NullPointerException());
        // 全失败任务也要能收尾，否则本用例的收尾断言不成立
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        // 不卡 RUNNING、不安排重试
        verify(genTaskItemMapper, never()).rescheduleForRetry(any(), any(Integer.class),
                any(), any(), any());
        verify(progressPublisher).onItemDone(any(GenTask.class), any(GenTaskItem.class),
                any(ItemOutcome.class));

        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        List<Object> values = setValues(wrapper.getValue());

        // ① item 置 FAILED ② error_code 非空 ③ error_msg 走 safeErrorMsg 兜底且非空
        assertThat(values).contains("FAILED", 3, "E_INTERNAL", "执行失败");
        // ④ 绝不能把 Java 的 null 字面量写进 NOT NULL 的 error_msg
        assertThat(values)
                .filteredOn(v -> v instanceof String)
                .map(String.class::cast)
                .noneMatch(s -> s.contains("null"));
    }

    // ------------------------------------------------------------------
    // T07-2 修复：模型调用失败必须保住 LlmException 的错误码
    // ------------------------------------------------------------------

    @Test
    @DisplayName("T07-2 LlmException 透传：第 3 次失败 → item FAILED + error_code=E_LLM_UPSTREAM（不是 E_INTERNAL）")
    void llmExceptionKeepsItsErrorCodeOnPermanentFailure() throws Exception {
        // attempts 已到 2 → 本次是第 3 次，走 failPermanentlyWithOutcome
        when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem(2));
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenThrow(new LlmException(ErrorCode.E_LLM_UPSTREAM, "上游超时", true));
        when(genTaskItemMapper.countByStatus(TASK_ID)).thenReturn(List.of(row("FAILED", 1)));
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class))).thenReturn(1);

        worker.run(ITEM_ID);

        // ① item 终态 FAILED；② error_code 是 LlmException 的原码而不是 E_INTERNAL；
        // ③ attempts=3（透传错误码**不影响**重试次数，retryable=false 也照旧重试 3 次）
        ArgumentCaptor<Wrapper<GenTaskItem>> wrapper = updateWrapperCaptor();
        verify(genTaskItemMapper).update(isNull(), wrapper.capture());
        List<Object> values = setValues(wrapper.getValue());
        assertThat(values).contains("FAILED", 3, "E_LLM_UPSTREAM", "上游超时");
        assertThat(values).doesNotContain("E_INTERNAL");
        // 到上限就不再安排重试
        verify(genTaskItemMapper, never()).rescheduleForRetry(any(), any(Integer.class),
                any(), any(), any());

        // D-42 边界锁：**任务级** error_code 仍然恒 E_INTERNAL（本批明确不修，别顺手改）
        verify(genTaskMapper).markTerminal(eq(TASK_ID), eq(TaskStatus.FAILED.code()),
                eq("E_INTERNAL"), eq("有 1 条子项失败"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("T07-2 LlmException 透传（退避路径）：第 1 次失败 → 重试行也记 E_LLM_UPSTREAM")
    void llmExceptionKeepsItsErrorCodeOnRetryRow() throws Exception {
        // 这条守的是"重试中的那两次也不能丢码"：t07degrade 若按 error_code 统计上游失败
        // 次数，只有第 3 次是 E_LLM_UPSTREAM、前两次是 E_INTERNAL 的话，指标依然不可用
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenThrow(new LlmException(ErrorCode.E_LLM_UPSTREAM, "上游超时", true));

        worker.run(ITEM_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskItemMapper).rescheduleForRetry(eq(ITEM_ID), eq(1),
                any(LocalDateTime.class), code.capture(), msg.capture());
        assertThat(code.getValue()).isEqualTo("E_LLM_UPSTREAM");
        assertThat(msg.getValue()).isEqualTo("上游超时");
        // 未到上限 → 不是 item 终态，不收尾
        verify(genTaskMapper, never()).markTerminal(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("T07-2 回归：非 LlmException 的 RuntimeException 仍兜底成 E_INTERNAL（原宽 catch 行为不变）")
    void runtimeExceptionStillFallsBackToInternal() throws Exception {
        when(handler.execute(any(GenTask.class), any(GenTaskItem.class)))
                .thenThrow(new IllegalStateException("CaseDraft 与 schema 字段漂移"));

        worker.run(ITEM_ID);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(genTaskItemMapper).rescheduleForRetry(eq(ITEM_ID), eq(1),
                any(LocalDateTime.class), code.capture(), msg.capture());
        assertThat(code.getValue()).isEqualTo("E_INTERNAL");
        assertThat(msg.getValue()).isEqualTo("CaseDraft 与 schema 字段漂移");
    }
}
