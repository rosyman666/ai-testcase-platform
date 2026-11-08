package com.aittest.generation.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 调度器：定时认领待执行子项，投给线程池；并每 60s 节流回收超时任务。
 *
 * <p><b>为什么是轮询而不是事件驱动</b>：单机部署下轮询已经够快（500ms 一轮），
 * 而事件驱动需要额外的唤醒机制（消息队列 / 条件变量 + 跨进程通知）。
 * 真正的收益只有在"任务到达间隔远大于轮询间隔"时才体现，而抽点/生成都是
 * 人为触发、间隔以分钟计。<b>先简单，等出现"延迟不可接受"再说</b>。</p>
 *
 * <p><b>认领数量按线程池空闲槽位算</b>，不是固定批量：写死 16 条而池子只有 8 个线程时，
 * 多出来的 8 条会被 {@code CallerRunsPolicy} 推回调度线程执行——调度线程被一个
 * 30 秒的模型调用卡住，整个队列停摆，而日志里只看到"认领了 16 条"。</p>
 *
 * <p><b>超时回收每 60s 节流（I7）</b>：poll() 每 500ms 一轮，若每轮都查
 * {@code deadline_at < now} 就是 2 次/秒的全表条件扫描，纯浪费。worker 崩溃会让
 * RUNNING item 永停，{@link GenTaskWorker#finalizeIfDone(Long)} 又因 {@code running > 0}
 * 永不收尾——回收器把卡住的 item 置 FAILED 后逐个触发收尾判定，任务才能正常收敛。</p>
 *
 * @author 寇豆码
 */
@Service
public class GenTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GenTaskDispatcher.class);

    /** 单轮最多认领条数（即便池子很大也不一次吃太多，避免饿死后来的任务）。 */
    private static final int MAX_CLAIM_PER_ROUND = 16;

    /** 超时回收节流间隔（纳秒）：60 秒。 */
    private static final long RECYCLE_INTERVAL_NANOS = 60L * 1_000_000_000L;

    private final GenTaskService genTaskService;

    private final GenTaskWorker genTaskWorker;

    private final ThreadPoolTaskExecutor executor;

    /** worker 标识：{@code <host>-<seq>}，认领行的 {@code worker_id} 写它，排障用。 */
    private final String workerId;

    private final AtomicLong claimSeq = new AtomicLong();

    /** 上次超时回收时刻（纳秒）；0 表示尚未跑过。 */
    private final AtomicLong lastRecycleNanos = new AtomicLong(0L);

    public GenTaskDispatcher(GenTaskService genTaskService,
                             GenTaskWorker genTaskWorker,
                             ThreadPoolTaskExecutor aiTaskExecutor) {
        this.genTaskService = genTaskService;
        this.genTaskWorker = genTaskWorker;
        this.executor = aiTaskExecutor;
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            host = "unknown";
        }
        this.workerId = host + "-" + ProcessHandle.current().pid();
    }

    /**
     * 一轮调度：节流回收超时任务 → 按空闲槽位认领 → 投给线程池。
     *
     * <p>吞掉所有异常——调度线程挂了整个系统就再也不消费了，
     * 所以宁可打 ERROR 日志继续下一轮，也不要让一次偶发异常终止调度。</p>
     */
    @Scheduled(fixedDelay = 500)
    public void poll() {
        try {
            recycleExpiredIfDue();
            int free = executor.getMaxPoolSize() - executor.getActiveCount();
            if (free <= 0) {
                return;
            }
            int limit = Math.min(free, MAX_CLAIM_PER_ROUND);
            List<Long> ids = genTaskService.claimBatch(limit, workerId + "#" + claimSeq.incrementAndGet());
            if (ids.isEmpty()) {
                return;
            }
            log.debug("[SCHED] 认领 {} 条，投递线程池（空闲槽位 {}）", ids.size(), free);
            for (Long id : ids) {
                executor.execute(() -> {
                    try {
                        genTaskWorker.run(id);
                    } catch (Exception ex) {
                        // run 内部已兜住可预期的失败；这里只防"兜底本身挂了"
                        log.error("[SCHED] 子项执行未捕获异常 id={} 原因={}", id, ex.getMessage(), ex);
                    }
                });
            }
        } catch (Exception ex) {
            log.error("[SCHED] 调度轮询异常，下一轮继续：{}", ex.getMessage(), ex);
        }
    }

    /**
     * 每 60s 一次的超时回收：先把卡住的 RUNNING item 置 FAILED，再逐任务触发收尾判定。
     *
     * <p>用 CAS 推进时间戳，避免多个调度线程同时跑回收；收尾判定放在循环里逐个调，
     * 单个任务失败不影响其余任务。</p>
     */
    private void recycleExpiredIfDue() {
        long now = System.nanoTime();
        long last = lastRecycleNanos.get();
        if (last != 0L && now - last < RECYCLE_INTERVAL_NANOS) {
            return;
        }
        if (!lastRecycleNanos.compareAndSet(last, now)) {
            return;
        }
        try {
            List<Long> taskIds = genTaskService.recycleExpiredTasks(LocalDateTime.now(ZoneOffset.UTC));
            for (Long taskId : taskIds) {
                try {
                    genTaskWorker.finalizeIfDone(taskId);
                } catch (Exception ex) {
                    log.error("[SCHED] 超时任务收尾失败 taskId={} 原因={}", taskId, ex.getMessage(), ex);
                }
            }
        } catch (Exception ex) {
            log.error("[SCHED] 超时回收异常，下一轮继续：{}", ex.getMessage(), ex);
        }
    }
}
