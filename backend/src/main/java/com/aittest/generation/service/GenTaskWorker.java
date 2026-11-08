package com.aittest.generation.service;

import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskItemMapper;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 执行一条任务子项：<b>认领之后、收尾之前</b>的那一段。
 *
 * <p><b>四条本类独有的约束</b>（每条都对应一条会静默出错的失效模式）：</p>
 * <ol>
 *   <li><b>失败退避靠后移 {@code next_run_at}，不靠 {@code sleep}</b>。sleep 会占住线程池里的线程，
 *       8 个并发线程很快全在睡觉，队列看着有货却没人认领——表现为"任务卡住"，
 *       而 CPU 与日志都显示一切正常。退避间隔 {@code 2^attempts} 秒，上限 60 秒。</li>
 *   <li><b>{@code attempts} 上限 3</b>，超限置 {@code FAILED} 而不是继续重试。
 *       无限重试的坏输出会把成本台账烧穿，而每一次都"只是再试一次"。</li>
 *   <li><b>item 终态写入带 {@code status='RUNNING'} 守卫并检查影响行数</b>。cancel 会把未认领
 *       item 置 {@code SKIPPED}；一个已经过了 {@code run()} 开头检查、正在跑 LLM 的 in-flight
 *       worker 若用"只按 id"的 update 覆盖回来，就会把取消语义抹掉，并让收尾逻辑误以为
 *       "所有单元都成功了"。0 行说明这条 item 已被别的路径收尾，本次直接放弃记账与收尾。</li>
 *   <li><b>任务级收尾只在抢到 {@code markTerminal} 的收尾权之后、恰好一次</b>。
 *       {@code markTerminal} 带 {@code status IN ('PENDING','RUNNING')} 守卫，8 个 worker
 *       可能同时判定"都跑完了"，没有守卫就会重复收尾、重复推 done、重复调
 *       {@code onTaskFinalize}（抽点场景下那会重复跑一次文档级 supersedeMissing）。
 *       更重要的是：<b>已取消的任务必然抢不到收尾权</b>，于是绝对不会触发
 *       "只带一个成功单元 kept 的取代"——那会把整篇文档其余 ACTIVE 测试点误置 SUPERSEDED。</li>
 * </ol>
 *
 * <p><b>收尾判定统一收敛在 {@code run()} 末尾</b>：SUCCESS / FAILED / SKIPPED 三种 item
 * 终态分支都会走 {@link #finalizeIfDone(Long)}，不再只有成功分支才收尾。否则"全部失败"或
 * "无处理器"的任务最后一条 item 结束后永远停在 PENDING，SSE 等不到 done/error（B3）。</p>
 *
 * <p><b>预期内的失败不抛异常</b>（与 T05 的 {@code safeExtract} 同一条纪律）：
 * 处理器把模型坏输出、schema 不合规收成 {@link ItemOutcome#failed}，本类负责记账与重试决策。
 * 真正的程序错误（NPE 之类）走 {@code catch (Exception)}，同样记账但视为可重试。</p>
 *
 * @author 寇豆码
 */
@Service
public class GenTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(GenTaskWorker.class);

    /** 单条 item 的最大尝试次数。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 退避上限（秒）。 */
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final GenTaskMapper genTaskMapper;

    private final GenTaskItemMapper genTaskItemMapper;

    private final GenTaskService genTaskService;

    private final ProgressPublisher progressPublisher;

    private final Map<String, TaskItemHandler> handlers;

    public GenTaskWorker(GenTaskMapper genTaskMapper,
                         GenTaskItemMapper genTaskItemMapper,
                         GenTaskService genTaskService,
                         ProgressPublisher progressPublisher,
                         List<TaskItemHandler> handlerList) {
        this.genTaskMapper = genTaskMapper;
        this.genTaskItemMapper = genTaskItemMapper;
        this.genTaskService = genTaskService;
        this.progressPublisher = progressPublisher;
        this.handlers = new java.util.HashMap<>();
        for (TaskItemHandler h : handlerList) {
            TaskItemHandler prev = this.handlers.put(h.scene().code(), h);
            if (prev != null) {
                // 同场景两个实现会让路由静默二选一，必须启动期就炸出来
                throw new IllegalStateException("任务场景 " + h.scene().code()
                        + " 注册了多个 TaskItemHandler：" + prev.getClass().getName()
                        + " 与 " + h.getClass().getName());
            }
        }
    }

    /**
     * 执行一条已认领（{@code RUNNING}）的子项，并在末尾统一做任务级收尾判定。
     *
     * @param itemId 子项 id
     */
    public void run(Long itemId) {
        GenTaskItem item = genTaskItemMapper.selectById(itemId);
        if (item == null) {
            log.warn("[TASK] 子项不存在 id={}", itemId);
            return;
        }
        GenTask task = genTaskService.require(item.getTaskId());
        // 所有 return 收敛到这一处：终态 item（SUCCESS/FAILED/SKIPPED）才会触发收尾判定
        boolean itemTerminal = isFinalized(task)
                ? skipQuietly(task, item)
                : executeAndSettle(task, item);
        if (itemTerminal) {
            finalizeIfDone(task);
        }
    }

    /**
     * 任务是否已进入终态（终态集合以 {@link TaskStatus#isTerminal()} 为准，不写字符串比较）。
     *
     * @param task 任务
     * @return 已终态返回 true
     */
    private static boolean isFinalized(GenTask task) {
        return TaskStatus.valueOf(task.getStatus()).isTerminal();
    }

    /**
     * 执行一条非终态任务下的 item，并返回它是否落到了终态。
     *
     * @param task 任务
     * @param item 子项
     * @return true 表示本次把 item 写成了 SUCCESS/FAILED，调用方应做收尾判定
     */
    private boolean executeAndSettle(GenTask task, GenTaskItem item) {
        TaskItemHandler handler = handlers.get(task.getScene());
        if (handler == null) {
            return failPermanently(task, item, "E_INTERNAL",
                    "没有为场景 " + task.getScene() + " 注册处理器");
        }

        long startNanos = System.nanoTime();
        ItemOutcome outcome;
        try {
            outcome = handler.execute(task, item);
        } catch (Exception ex) {
            int latency = elapsedMs(startNanos);
            log.warn("[TASK] 子项执行抛异常 taskNo={} unitRef={} 原因={}",
                    task.getTaskNo(), item.getUnitRef(), ex.getMessage());
            // NPE 的 getMessage() 常为 null；终态分支有兜底，这里不提前判空
            outcome = ItemOutcome.failed("E_INTERNAL", ex.getMessage(), latency);
        }

        if (outcome.success()) {
            return completeItem(task, item, outcome);
        }
        int attempts = (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
        if (attempts >= MAX_ATTEMPTS) {
            return failPermanentlyWithOutcome(task, item, outcome, attempts);
        }
        // 退避后重试：退回 PENDING 并后移 next_run_at，**不 sleep**，也不是 item 终态
        LocalDateTime nextRunAt = LocalDateTime.now(ZoneOffset.UTC)
                .plusSeconds(backoffSeconds(attempts));
        genTaskItemMapper.rescheduleForRetry(item.getId(), attempts, nextRunAt,
                safeErrorCode(outcome.errorCode()), safeErrorMsg(outcome.errorMsg()));
        log.info("[TASK] 子项退避重试 taskNo={} unitRef={} 第 {} 次，下次 {}",
                task.getTaskNo(), item.getUnitRef(), attempts, nextRunAt);
        return false;
    }

    /**
     * 成功收尾：写度量（含 {@code attempts}）+ 推进度。
     *
     * @return true 表示 update 命中了 RUNNING 行，本次记账有效
     */
    private boolean completeItem(GenTask task, GenTaskItem item, ItemOutcome outcome) {
        int attempts = (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
        int rows = genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getId, item.getId())
                .eq(GenTaskItem::getStatus, "RUNNING")
                .set(GenTaskItem::getStatus, "SUCCESS")
                .set(GenTaskItem::getAttempts, attempts)
                .set(GenTaskItem::getCaseCount, outcome.produced())
                .set(GenTaskItem::getDegraded, outcome.degraded())
                .set(GenTaskItem::getPromptTokens, outcome.promptTokens())
                .set(GenTaskItem::getCompletionTokens, outcome.completionTokens())
                .set(GenTaskItem::getCostMicro, outcome.costMicro())
                .set(GenTaskItem::getLatencyMs, outcome.latencyMs())
                .set(GenTaskItem::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC))
                .set(GenTaskItem::getErrorCode, "")
                .set(GenTaskItem::getErrorMsg, ""));
        if (rows == 0) {
            log.warn("[TASK] 成功记账未命中 RUNNING item（可能已被取消）itemId={} taskNo={}",
                    item.getId(), task.getTaskNo());
            return false;
        }
        item.setAttempts(attempts);
        progressPublisher.onItemDone(task, item, outcome);
        return true;
    }

    /**
     * 预期内失败且已到重试上限：记账后按终态处理。
     *
     * @return true 表示 update 命中了 RUNNING 行
     */
    private boolean failPermanentlyWithOutcome(GenTask task, GenTaskItem item,
                                               ItemOutcome outcome, int attempts) {
        String errorCode = safeErrorCode(outcome.errorCode());
        String errorMsg = safeErrorMsg(outcome.errorMsg());
        int rows = genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getId, item.getId())
                .eq(GenTaskItem::getStatus, "RUNNING")
                .set(GenTaskItem::getStatus, "FAILED")
                .set(GenTaskItem::getAttempts, attempts)
                .set(GenTaskItem::getLatencyMs, outcome.latencyMs())
                .set(GenTaskItem::getErrorCode, errorCode)
                .set(GenTaskItem::getErrorMsg, errorMsg)
                .set(GenTaskItem::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (rows == 0) {
            log.warn("[TASK] 失败记账未命中 RUNNING item（可能已被取消）itemId={} taskNo={}",
                    item.getId(), task.getTaskNo());
            return false;
        }
        item.setAttempts(attempts);
        progressPublisher.onItemDone(task, item,
                ItemOutcome.failed(errorCode, errorMsg, outcome.latencyMs()));
        return true;
    }

    /**
     * 程序错误等无法重试的失败：直接终态。
     *
     * @return true 表示 update 命中了 RUNNING 行
     */
    private boolean failPermanently(GenTask task, GenTaskItem item, String errorCode, String errorMsg) {
        int attempts = (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
        int rows = genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getId, item.getId())
                .eq(GenTaskItem::getStatus, "RUNNING")
                .set(GenTaskItem::getStatus, "FAILED")
                .set(GenTaskItem::getAttempts, attempts)
                .set(GenTaskItem::getErrorCode, safeErrorCode(errorCode))
                .set(GenTaskItem::getErrorMsg, safeErrorMsg(errorMsg))
                .set(GenTaskItem::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (rows == 0) {
            return false;
        }
        progressPublisher.onItemDone(task, item,
                ItemOutcome.failed(safeErrorCode(errorCode), safeErrorMsg(errorMsg), 0));
        return true;
    }

    /**
     * 任务已终态时静默跳过：不计成功也不计失败，但仍计入 {@code done_items}。
     *
     * @return true 表示 update 命中了 RUNNING 行
     */
    private boolean skipQuietly(GenTask task, GenTaskItem item) {
        int rows = genTaskItemMapper.update(null, Wrappers.<GenTaskItem>lambdaUpdate()
                .eq(GenTaskItem::getId, item.getId())
                .eq(GenTaskItem::getStatus, "RUNNING")
                .set(GenTaskItem::getStatus, "SKIPPED")
                .set(GenTaskItem::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (rows == 0) {
            return false;
        }
        progressPublisher.onItemSkipped(task, item);
        return true;
    }

    /**
     * 供 dispatcher 的超时回收器复用：按 taskId 重新判定一次是否该收尾。
     *
     * @param taskId 任务 id
     */
    public void finalizeIfDone(Long taskId) {
        GenTask task = genTaskService.require(taskId);
        finalizeIfDone(task);
    }

    /**
     * 内部统一入口：已终态任务直接返回（cancel 已推过终态事件，不能重复收尾）。
     *
     * @param task 任务
     */
    private void finalizeIfDone(GenTask task) {
        if (isFinalized(task)) {
            return;
        }
        maybeFinalize(task);
    }

    /**
     * 判断是否所有子项都已收尾，是则收尾整个任务（<b>只会有一次真正执行</b>）。
     *
     * <p>{@code markTerminal} 带状态守卫，8 个 worker 同时进来也只有一个赢；
     * 任务级收尾动作（{@code onTaskFinalize}）必须在赢之后才执行，否则取消竞态会造成
     * 文档级误取代且无法回滚。</p>
     */
    private void maybeFinalize(GenTask task) {
        List<Map<String, Object>> counts = genTaskItemMapper.countByStatus(task.getId());
        int pending = 0;
        int running = 0;
        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (Map<String, Object> row : counts) {
            String status = String.valueOf(row.get("status"));
            int cnt = ((Number) row.get("cnt")).intValue();
            switch (status) {
                case "PENDING" -> pending += cnt;
                case "RUNNING" -> running += cnt;
                case "SUCCESS" -> success += cnt;
                case "FAILED" -> failed += cnt;
                default -> skipped += cnt;
            }
        }
        if (pending > 0 || running > 0) {
            return;
        }
        // 全部收尾：先抢收尾权（带状态守卫的 markTerminal），抢到才做任务级动作
        TaskStatus terminal = TaskStateMachine.terminalOf(success + failed + skipped, success);
        // error_code / error_msg 是 NOT NULL 列，"无错误"用空串而不是 null：
        // 传 null 会被 MySQL 严格模式拒掉，异常又会被调度器的兜底 catch 吞成一条 ERROR 日志
        String errorCode = failed > 0 ? "E_INTERNAL" : "";
        String errorMsg = failed > 0 ? ("有 " + failed + " 条子项失败") : "";
        int won = genTaskMapper.markTerminal(task.getId(), terminal.code(),
                errorCode, errorMsg, LocalDateTime.now(ZoneOffset.UTC));
        if (won <= 0) {
            // 没抢到收尾权：任务可能已被用户取消或已被别的 worker 收尾。
            // 这里绝对不能调 onTaskFinalize —— 取消了就没有"本轮产出全集"可用。
            log.info("[TASK] 未抢到收尾权，跳过任务级收尾 taskNo={} 期望终态={}",
                    task.getTaskNo(), terminal.code());
            return;
        }

        task.setStatus(terminal.code());
        task.setDoneItems(success + failed + skipped);
        task.setSuccessItems(success);
        task.setFailedItems(failed);

        TaskItemHandler handler = handlers.get(task.getScene());
        if (handler != null) {
            try {
                handler.onTaskFinalize(task, terminal);
            } catch (Exception ex) {
                log.error("[TASK] 任务级收尾失败 taskNo={} 原因={}", task.getTaskNo(), ex.getMessage(), ex);
            }
        }
        progressPublisher.refreshProgressField(task);
        progressPublisher.finish(task, terminal, errorCode, errorMsg);
    }

    /** {@code error_code} NOT NULL 兜底：null / 空白一律收敛成 {@code E_INTERNAL}。 */
    private static String safeErrorCode(String code) {
        return code == null || code.isBlank() ? "E_INTERNAL" : code;
    }

    /** {@code error_msg} NOT NULL 兜底：null / 空白一律收敛成可读中文。 */
    private static String safeErrorMsg(String msg) {
        return msg == null || msg.isBlank() ? "执行失败" : msg;
    }

    /** 退避间隔（秒）：{@code 2^attempts}，上限 60。 */
    private long backoffSeconds(int attempts) {
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 6));
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
