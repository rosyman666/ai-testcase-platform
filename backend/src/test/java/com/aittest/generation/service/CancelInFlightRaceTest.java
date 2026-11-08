package com.aittest.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.doc.mapper.DocSourceMapper;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.aittest.generation.support.MybatisPlusLambdaCache;
import com.aittest.testpoint.service.ExtractPlanner;
import com.aittest.testpoint.service.TestPointExtractService;
import com.aittest.testpoint.service.TestPointService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <b>B1 的确定性护栏</b>：cancel 与 in-flight worker 的竞态。
 *
 * <p>黑盒 e2e 里 mock provider 单次约 1ms，「worker 已过 {@code run()} 入口终态检查、
 * 还没 {@code completeItem()}」这个窗口是概率事件，只能得到 INCONCLUSIVE。这里用
 * {@link CountDownLatch} 把 stub handler 钉死在 {@code execute()} 里，主线程在窗口内
 * 完成取消，再放行——窗口是<b>构造出来的</b>，每次必现。</p>
 *
 * <p>与 {@link GenTaskWorkerTest} 的差别：那里 handler 是纯 mock，只能断言
 * {@code onTaskFinalize} 未被调用；这里把<b>真实的</b> {@link TestPointExtractHandler}
 * 挂到收尾回调上，于是可以直接断言「文档级 {@code supersedeMissing} 一次都没跑」，
 * 也就是 4 条哨兵 ACTIVE 测试点仍为 ACTIVE。</p>
 *
 * <p><b>正对照不可省</b>：第二条用例不放 cancel，用同一套装置证明收尾链路确实会调
 * {@code supersedeMissing} 且 kept 含全部 4 条哨兵——否则第一条的「never」可能只是
 * stub 没配好造成的假绿（否定性结论必须有正对照）。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CancelInFlightRaceTest {

    private static final Long TASK_ID = 7100L;

    private static final Long ITEM_ID = 7200L;

    private static final Long DOC_ID = 9001L;

    /** 4 条哨兵 ACTIVE 测试点的去重键：取消竞态下它们绝不能被置 SUPERSEDED。 */
    private static final List<String> SENTINEL_HASHES =
            List.of("sentinel-1", "sentinel-2", "sentinel-3", "sentinel-4");

    @Mock
    private GenTaskMapper genTaskMapper;

    @Mock
    private GenTaskItemMapper genTaskItemMapper;

    @Mock
    private GenTaskService genTaskService;

    @Mock
    private ProgressPublisher progressPublisher;

    @Mock
    private ExtractPlanner extractPlanner;

    @Mock
    private TestPointExtractService extractService;

    @Mock
    private TestPointService testPointService;

    @Mock
    private DocSourceMapper docSourceMapper;

    @BeforeAll
    static void installLambdaCache() {
        // worker 的 completeItem() 拼 LambdaUpdateWrapper
        MybatisPlusLambdaCache.install();
    }

    @BeforeEach
    void setUp() {
        // 主线程与 worker 线程都会用到；部分用例的收尾会被取消挡掉，故一律 lenient
        lenient().when(genTaskItemMapper.selectById(ITEM_ID)).thenReturn(newItem());
        lenient().when(genTaskService.require(TASK_ID))
                .thenReturn(newTask(TaskStatus.RUNNING.name()));
        lenient().when(genTaskItemMapper.update(ArgumentMatchers.<GenTaskItem>isNull(),
                ArgumentMatchers.<Wrapper<GenTaskItem>>any())).thenReturn(1);
        // 只有"抢到收尾权"的正对照会读它；负向用例读不到，lenient 避免 UnnecessaryStubbing
        lenient().when(genTaskItemMapper.selectList(any())).thenReturn(sentinelItems());
    }

    private static GenTaskItem newItem() {
        GenTaskItem item = new GenTaskItem();
        item.setId(ITEM_ID);
        item.setTaskId(TASK_ID);
        item.setUnitRef("operation:createUser");
        item.setStatus("RUNNING");
        item.setAttempts(0);
        return item;
    }

    private static GenTask newTask(String status) {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo("T20261107-000001");
        t.setStatus(status);
        t.setScene(TaskScene.TESTPOINT_EXTRACT.code());
        t.setDocId(DOC_ID);
        t.setTotalItems(4);
        return t;
    }

    private static List<GenTaskItem> sentinelItems() {
        List<GenTaskItem> items = new ArrayList<>();
        for (int i = 0; i < SENTINEL_HASHES.size(); i++) {
            GenTaskItem item = new GenTaskItem();
            item.setId(7300L + i);
            item.setTaskId(TASK_ID);
            item.setStatus("SUCCESS");
            item.setUnitRef("operation:unit" + (i + 1));
            item.setKeptHashes(SENTINEL_HASHES.get(i));
            items.add(item);
        }
        return items;
    }

    private static Map<String, Object> row(String status, int cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("cnt", cnt);
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Set<String>> setCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Set.class);
    }

    private TestPointExtractHandler realHandler() {
        return new TestPointExtractHandler(extractPlanner, extractService, testPointService,
                docSourceMapper, genTaskItemMapper);
    }

    /**
     * 构造 worker：{@code markTerminal} 的返回值由 DB 里的任务状态决定，
     * 模拟真实的状态守卫 {@code AND status IN ('PENDING','RUNNING')}。
     */
    private GenTaskWorker newWorker(AtomicReference<String> dbTaskStatus,
                                    BlockingExtractHandler blocking) {
        when(genTaskMapper.markTerminal(eq(TASK_ID), any(String.class), any(String.class),
                any(String.class), any(LocalDateTime.class)))
                .thenAnswer(invocation ->
                        TaskStatus.RUNNING.code().equals(dbTaskStatus.get()) ? 1 : 0);
        when(genTaskItemMapper.countByStatus(TASK_ID))
                .thenReturn(List.of(row("SUCCESS", SENTINEL_HASHES.size())));
        return new GenTaskWorker(genTaskMapper, genTaskItemMapper, genTaskService,
                progressPublisher, List.of(blocking));
    }

    private static Thread startWorker(GenTaskWorker worker, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> {
            try {
                worker.run(ITEM_ID);
            } catch (Throwable t) {
                error.set(t);
            }
        }, "t06-cancel-inflight-worker");
        thread.start();
        return thread;
    }

    @Test
    @DisplayName("B1 确定性竞态：cancel 发生在 handler 执行中 → 4 条哨兵仍 ACTIVE、任务保持 CANCELED、supersedeMissing 零调用")
    void cancelWhileHandlerInFlightKeepsSentinelsActive() throws Exception {
        AtomicReference<String> dbTaskStatus = new AtomicReference<>(TaskStatus.RUNNING.code());
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch cancelApplied = new CountDownLatch(1);
        BlockingExtractHandler blocking =
                new BlockingExtractHandler(realHandler(), handlerEntered, cancelApplied);
        GenTaskWorker worker = newWorker(dbTaskStatus, blocking);

        Thread workerThread = startWorker(worker, workerError);
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS))
                .as("worker 应已进入 execute()，即已过 run() 入口的终态检查").isTrue();

        // 主线程在真实竞态窗口内完成取消：worker 手里仍是 RUNNING 的旧 task 快照，
        // 但 DB 状态已被置成 CANCELED，markTerminal 的状态守卫必然返回 0。
        dbTaskStatus.set(TaskStatus.CANCELED.code());
        cancelApplied.countDown();
        workerThread.join(5_000L);

        assertThat(workerThread.isAlive()).isFalse();
        assertThat(workerError.get()).isNull();
        assertThat(dbTaskStatus.get()).isEqualTo(TaskStatus.CANCELED.code());
        // 收尾判定确实跑到了（防止"根本没走到"造成的假绿）
        verify(genTaskMapper, times(1)).markTerminal(eq(TASK_ID), any(String.class),
                any(String.class), any(String.class), any(LocalDateTime.class));
        verify(progressPublisher).onItemDone(any(GenTask.class), any(GenTaskItem.class),
                any(ItemOutcome.class));
        // 三道防线：任务级收尾回调一次都没进 → 文档级 supersedeMissing 零调用
        // → 4 条哨兵 ACTIVE 测试点不会被带残缺 kept 误置 SUPERSEDED
        assertThat(blocking.finalizeCalls()).isZero();
        verify(genTaskItemMapper, never()).selectList(any());
        verify(testPointService, never()).supersedeMissing(any(), any());
        verify(progressPublisher, never()).finish(any(GenTask.class), any(TaskStatus.class),
                any(String.class), any(String.class));
        verify(progressPublisher, never()).refreshProgressField(any(GenTask.class));
    }

    @Test
    @DisplayName("正对照：无 cancel 时同一套装置必须调用一次 supersedeMissing，且 kept 含全部 4 条哨兵")
    void withoutCancelFinalizeSupersedesWithCompleteKept() throws Exception {
        AtomicReference<String> dbTaskStatus = new AtomicReference<>(TaskStatus.RUNNING.code());
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch cancelApplied = new CountDownLatch(1);
        BlockingExtractHandler blocking =
                new BlockingExtractHandler(realHandler(), handlerEntered, cancelApplied);
        GenTaskWorker worker = newWorker(dbTaskStatus, blocking);

        Thread workerThread = startWorker(worker, workerError);
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        // 不放取消：dbTaskStatus 保持 RUNNING，markTerminal 返回 1
        cancelApplied.countDown();
        workerThread.join(5_000L);

        assertThat(workerThread.isAlive()).isFalse();
        assertThat(workerError.get()).isNull();
        assertThat(blocking.finalizeCalls()).isEqualTo(1);
        ArgumentCaptor<Set<String>> kept = setCaptor();
        verify(testPointService, times(1)).supersedeMissing(eq(DOC_ID), kept.capture());
        assertThat(kept.getValue()).containsExactlyInAnyOrderElementsOf(SENTINEL_HASHES);
        verify(progressPublisher).finish(any(GenTask.class), eq(TaskStatus.SUCCESS),
                eq(""), eq(""));
    }

    /**
     * 阻塞在 {@code execute()} 里的 handler；{@code onTaskFinalize} 委托给真实的
     * {@link TestPointExtractHandler}，这样"文档级取代是否被触发"可直接 verify。
     */
    private static final class BlockingExtractHandler implements TaskItemHandler {

        private final TestPointExtractHandler delegate;

        private final CountDownLatch entered;

        private final CountDownLatch proceed;

        private final AtomicInteger finalizeCalls = new AtomicInteger();

        private BlockingExtractHandler(TestPointExtractHandler delegate,
                                       CountDownLatch entered, CountDownLatch proceed) {
            this.delegate = delegate;
            this.entered = entered;
            this.proceed = proceed;
        }

        @Override
        public TaskScene scene() {
            return TaskScene.TESTPOINT_EXTRACT;
        }

        @Override
        public ItemOutcome execute(GenTask task, GenTaskItem item) throws Exception {
            entered.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("主线程未在 5s 内放行，竞态用例超时");
            }
            return ItemOutcome.ok(2, 0, 0, 0, 1);
        }

        @Override
        public void onTaskFinalize(GenTask task, TaskStatus terminal) {
            finalizeCalls.incrementAndGet();
            delegate.onTaskFinalize(task, terminal);
        }

        private int finalizeCalls() {
            return finalizeCalls.get();
        }
    }
}
