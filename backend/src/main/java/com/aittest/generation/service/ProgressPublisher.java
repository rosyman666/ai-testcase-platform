package com.aittest.generation.service;

import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.domain.GenTaskItem;
import com.aittest.generation.mapper.GenTaskMapper;
import com.aittest.generation.service.TaskItemHandler.ItemOutcome;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 进度聚合与推送：<b>{@code gen_task} 表里的进度是权威值，SSE 只是它的投影</b>。
 *
 * <p><b>四条本类独有的约束</b>：</p>
 * <ol>
 *   <li><b>计数必须原子累加</b>（{@code GenTaskMapper.bumpCounters} 写 {@code col = col + n}，
 *       且 {@code produced} 累加到 {@code produced_items} 而不是 {@code accepted_cases}）。
 *       8 个 worker 同时收尾时"读-改-写"会互相覆盖，表现为进度条卡住不动——
 *       而每条日志都说"更新成功"。</li>
 *   <li><b>进度事件节流 ≥200ms</b>：一个 50 条 item 的任务会在几秒内打 50 次进度，
 *       浏览器重渲染的成本远高于那 200ms 的新鲜度损失。但<b>终态事件不节流</b>——
 *       节流它就可能永远发不出去。</li>
 *   <li><b>快照从 DB 现读，不缓存</b>：SSE 断线重连时要凭快照恢复进度，如果快照来自
 *       一份内存副本，它和 DB 漂了前端就会显示"98%"而任务其实已结束（§4.3）。</li>
 *   <li><b>执行期进度按 done/total 现算覆盖事件里的 progress 字段，不写 DB</b>（I3）。
 *       {@code progress} 列只在终态由 {@link #refreshProgressField} 落一次库；
 *       执行中每次都写库既浪费，又和"冗余字段只有一处写入"的纪律冲突。</li>
 * </ol>
 *
 * <p><b>{@code done}</b> 与 {@code total} 的口径：{@code done = success + failed + skipped}，
 * 与 {@code gen_task.done_items} 一致（DDL 注释"已完成 item 数（含成功与失败）"）。
 * SUCCESS / FAILED / SKIPPED 三种终态都会 bump，见 {@link #onItemSkipped}。</p>
 *
 * @author 寇豆码
 */
@Service
public class ProgressPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProgressPublisher.class);

    /** 进度事件最小间隔（毫秒）。 */
    private static final long PROGRESS_THROTTLE_MS = 200;

    private final GenTaskMapper genTaskMapper;

    private final SseEmitterRegistry sseEmitterRegistry;

    /** taskId → 上次推送进度的时刻（纳秒），用于节流。 */
    private final Map<Long, Long> lastPushNanos = new ConcurrentHashMap<>();

    public ProgressPublisher(GenTaskMapper genTaskMapper, SseEmitterRegistry sseEmitterRegistry) {
        this.genTaskMapper = genTaskMapper;
        this.sseEmitterRegistry = sseEmitterRegistry;
    }

    /**
     * 一条 item 成功/失败收尾后调用：落库计数 + 推 {@code item} 事件 + 节流推 {@code progress} 事件。
     *
     * @param task   所属任务
     * @param item   收尾的 item
     * @param outcome 执行结果
     */
    public void onItemDone(GenTask task, GenTaskItem item, ItemOutcome outcome) {
        int success = outcome.success() ? 1 : 0;
        int failed = outcome.success() ? 0 : 1;
        genTaskMapper.bumpCounters(task.getId(), 1, success, failed, outcome.produced());

        // item 终态事件：不节流，每条都要到（前端要逐条渲染）
        Map<String, Object> itemEvent = new LinkedHashMap<>();
        itemEvent.put("itemId", String.valueOf(item.getId()));
        itemEvent.put("testPointId", item.getTestPointId() == null ? null : String.valueOf(item.getTestPointId()));
        itemEvent.put("unitRef", item.getUnitRef());
        itemEvent.put("status", outcome.success() ? "SUCCESS" : "FAILED");
        itemEvent.put("caseCount", outcome.produced());
        itemEvent.put("attempts", item.getAttempts());
        itemEvent.put("errorCode", outcome.errorCode());
        itemEvent.put("errorMsg", outcome.errorMsg());
        itemEvent.put("latencyMs", outcome.latencyMs());
        sseEmitterRegistry.send(task.getId(), "item", itemEvent);

        pushProgress(task.getId());
    }

    /**
     * 一条 item 被跳过（任务已取消/收尾）后调用：仍计入 {@code done_items}，但不计成功也不计失败。
     *
     * <p>少了这一步，取消后的任务会出现 {@code done_items < total_items}、
     * 进度条永远差一截，而终态事件又已经推过了（B3）。</p>
     *
     * @param task 所属任务
     * @param item 被跳过的 item
     */
    public void onItemSkipped(GenTask task, GenTaskItem item) {
        genTaskMapper.bumpCounters(task.getId(), 1, 0, 0, 0);
        Map<String, Object> itemEvent = new LinkedHashMap<>();
        itemEvent.put("itemId", String.valueOf(item.getId()));
        itemEvent.put("unitRef", item.getUnitRef());
        itemEvent.put("status", "SKIPPED");
        sseEmitterRegistry.send(task.getId(), "item", itemEvent);
        pushProgress(task.getId());
    }

    /**
     * 推全量快照（首帧、以及断线重连时的恢复帧）。
     *
     * <p><b>总是从 DB 现读</b>：这是"断线重连能恢复进度"的唯一依据，不能用缓存。</p>
     *
     * @param taskId 任务
     */
    public void pushSnapshot(Long taskId) {
        sseEmitterRegistry.send(taskId, "snapshot", snapshotOf(taskId));
    }

    /**
     * 节流推一次进度事件。事件体里的 {@code progress} 按 done/total 现算覆盖，
     * <b>不写 DB</b>（I3）：执行期间 DB 的冗余 progress 列保持 0，只在终态落一次。
     *
     * @param taskId 任务
     */
    private void pushProgress(Long taskId) {
        if (!shouldPushProgress(taskId)) {
            return;
        }
        Map<String, Object> snap = snapshotOf(taskId);
        int total = intOf(snap.get("total"));
        int done = intOf(snap.get("done"));
        int progress = total <= 0 ? 0 : Math.min(100, (int) Math.round(done * 100.0 / total));
        snap.put("progress", progress);
        sseEmitterRegistry.send(taskId, "progress", snap);
    }

    /**
     * 任务收尾：推 {@code done}（或 {@code error}）→ 关闭全部连接。
     *
     * <p><b>本方法不再写 {@code markTerminal}</b>：终态写入由调用方带状态守卫地完成
     * （worker 用 {@code won > 0} 抢收尾权，cancel 用状态机校验后写），双写会让
     * "谁赢了收尾权"这个事实变得模糊。调用方<b>必须</b>在调本方法前已置终态。</p>
     *
     * <p><b>终态事件不节流</b>：它只发一次，节流它就可能永远发不出去，
     * 前端会停在 99% 直到 SSE 超时。</p>
     *
     * @param task     任务（调用方已置终态）
     * @param terminal 终态
     * @param errorCode 任务级错误码（可空串，但不可 null）
     * @param errorMsg  任务级错误说明（可空串，但不可 null）
     */
    public void finish(GenTask task, TaskStatus terminal, String errorCode, String errorMsg) {
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        task.setStatus(terminal.code());
        task.setFinishedAt(finishedAt);

        Map<String, Object> payload = snapshotOf(task.getId());
        if (terminal == TaskStatus.SUCCESS || terminal == TaskStatus.PARTIAL) {
            sseEmitterRegistry.send(task.getId(), "done", payload);
        } else {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("taskId", String.valueOf(task.getId()));
            err.put("errorCode", errorCode);
            err.put("errorMsg", errorMsg);
            err.put("summary", payload);
            sseEmitterRegistry.send(task.getId(), "error", err);
        }
        sseEmitterRegistry.complete(task.getId());
        lastPushNanos.remove(task.getId());
        log.info("[TASK] 任务收尾 taskNo={} status={} done={} total={}",
                task.getTaskNo(), terminal.code(), task.getDoneItems(), task.getTotalItems());
    }

    /**
     * 从 DB 现读构造快照（§4.3 的 {@code snapshot} 事件体）。
     *
     * @param taskId 任务
     * @return 快照；任务不存在时返回只含 taskId 的最小体
     */
    public Map<String, Object> snapshotOf(Long taskId) {
        GenTask t = genTaskMapper.selectById(taskId);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("taskId", String.valueOf(taskId));
        if (t == null) {
            return snap;
        }
        snap.put("taskNo", t.getTaskNo());
        snap.put("scene", t.getScene());
        snap.put("status", t.getStatus());
        snap.put("progress", intOf(t.getProgress()));
        snap.put("done", intOf(t.getDoneItems()));
        snap.put("total", intOf(t.getTotalItems()));
        snap.put("success", intOf(t.getSuccessItems()));
        snap.put("failed", intOf(t.getFailedItems()));
        // V6：produced 读本轮产出列，不再复用 accepted_cases（T07 采纳数）
        snap.put("produced", intOf(t.getProducedItems()));
        snap.put("startedAt", t.getStartedAt());
        snap.put("finishedAt", t.getFinishedAt());
        snap.put("errorCode", t.getErrorCode());
        snap.put("errorMsg", t.getErrorMsg());
        return snap;
    }

    /** Number / null 安全取 int。 */
    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /**
     * 进度事件是否该发（≥200ms 节流）。
     *
     * @param taskId 任务
     * @return 该发返回 true
     */
    private boolean shouldPushProgress(Long taskId) {
        long now = System.nanoTime();
        Long last = lastPushNanos.get(taskId);
        if (last != null && (now - last) < PROGRESS_THROTTLE_MS * 1_000_000L) {
            return false;
        }
        lastPushNanos.put(taskId, now);
        return true;
    }

    /**
     * 刷新冗余进度字段（列表页直读的那个 0-100，终态时落一次库）。
     *
     * @param task 任务
     */
    public void refreshProgressField(GenTask task) {
        int total = intOf(task.getTotalItems());
        int done = intOf(task.getDoneItems());
        int progress = total <= 0 ? 0 : Math.min(100, (int) Math.round(done * 100.0 / total));
        genTaskMapper.updateProgress(task.getId(), progress);
        task.setProgress(progress);
    }
}
