package com.aittest.generation.dto;

import com.aittest.generation.domain.GenTaskItem;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 任务子项出参（{@code GET /api/tasks/{id}/items} 的元素），T07-3 验收⑥ 的数据源。
 *
 * <p><b>为什么这个端点必须存在（而不是靠 SSE 就够）</b>：SSE 是<b>有损通道</b>。
 * {@code SseEmitterRegistry.send} 对 {@code IOException} 只打 DEBUG 不重投，而任务进入终态后
 * 新订阅只推 1 帧 {@code snapshot} 就 EOF——明细一条都拿不到。所以"逐 item 的耗时与错误码"
 * 的唯一权威来源是这张表的一次快照查询，<b>SSE 只做加速</b>（在线时少一次往返）。</p>
 *
 * <p><b>{@code errorCode} 是 item 级的那个码</b>（D-44 修好的），与任务级 {@code error_code}
 * 不是一个东西：任务级要聚合多 item 的错误码、规则未定，本批仍恒为 {@code E_INTERNAL}（D-42）。
 * 前端要显示"这条为什么失败"，必须读本字段，不能读任务级那个。</p>
 *
 * <p><b>{@code degraded} 输出 boolean 而不是 0/1</b>：列是 {@code TINYINT}（1 表示宽松解析
 * 降级产出）。原样输出 {@code degraded: 0} 时前端要写 {@code degraded === 1}，
 * 而任何一次"把 0 当假值"的直觉判断（{@code if (item.degraded)}）在 JS 里对
 * 字符串 {@code "0"} 都会出错——这里做成真正的布尔，语义没有二义性。
 * 指标的拆分口径（降级产出单独算采纳率）仍按"是否为 true"。</p>
 *
 * <p><b>时间输出 {@code Instant}</b>，与 {@link GenTaskVO} 完全同口径：库里是 UTC 的
 * {@code datetime(3)}，转 {@code Instant} 后由全局 Jackson 配置序列化，
 * 两条链路的形状一致，前端只需要一套时间处理。</p>
 *
 * @param id               item 主键（字符串，Long 全局序列化为字符串防精度丢失）
 * @param testPointId      关联测试点（{@code CASE_GENERATE} 场景有值，抽点场景为 null）
 * @param unitRef          待分析单元定位（{@code operation:xxx} / {@code section:n}；生成场景为 null）
 * @param status           {@code PENDING/RUNNING/SUCCESS/FAILED/SKIPPED}
 * @param attempts         已尝试次数（上限 3；超时回收置 FAILED 时<b>不</b> +1）
 * @param caseCount        本条 item 产出的用例数
 * @param degraded         是否走了宽松解析降级（{@code gen_task_item.degraded = 1}）
 * @param promptTokens     累计 prompt token
 * @param completionTokens 累计 completion token
 * @param costMicro        累计成本（微元，1 元 = 1_000_000）
 * @param latencyMs        耗时（毫秒）
 * @param errorCode        item 级错误码（成功为 null / 空串）
 * @param errorMsg         item 级错误说明（面向用户的中文）
 * @param startedAt        开始执行时间
 * @param finishedAt       进入终态时间
 * @author 寇豆码
 */
public record GenTaskItemVO(String id,
                            String testPointId,
                            String unitRef,
                            String status,
                            int attempts,
                            int caseCount,
                            boolean degraded,
                            int promptTokens,
                            int completionTokens,
                            long costMicro,
                            int latencyMs,
                            String errorCode,
                            String errorMsg,
                            Instant startedAt,
                            Instant finishedAt) {

    /** {@code degraded} 列的"是"取值（列是 TINYINT，见 {@code GenTaskItem.degraded}）。 */
    private static final int DEGRADED_YES = 1;

    /**
     * 由实体组装。
     *
     * <p>所有整型/长整型列都走"null → 0"：这些列在 V1 里是 {@code NOT NULL DEFAULT 0}，
     * 手工造的历史行或 {@code selectFewShot} 那类只填部分列的查询可能留下 null，
     * 让 VO 里出现 {@code null} 会让前端表格渲染出 {@code NaN}。</p>
     *
     * <p>{@code errorCode}/{@code errorMsg} <b>原样透出</b>（可能是 null 或空串）：
     * 它们本来就是 {@code NOT NULL DEFAULT ''}，"没有错误"就应该是空，
     * 编一个默认文案反而会让前端以为确实失败过。</p>
     *
     * @param item 实体
     * @return 出参
     */
    public static GenTaskItemVO of(GenTaskItem item) {
        return new GenTaskItemVO(
                item.getId() == null ? null : String.valueOf(item.getId()),
                item.getTestPointId() == null ? null : String.valueOf(item.getTestPointId()),
                item.getUnitRef(),
                item.getStatus(),
                item.getAttempts() == null ? 0 : item.getAttempts(),
                item.getCaseCount() == null ? 0 : item.getCaseCount(),
                item.getDegraded() != null && item.getDegraded() == DEGRADED_YES,
                item.getPromptTokens() == null ? 0 : item.getPromptTokens(),
                item.getCompletionTokens() == null ? 0 : item.getCompletionTokens(),
                item.getCostMicro() == null ? 0L : item.getCostMicro(),
                item.getLatencyMs() == null ? 0 : item.getLatencyMs(),
                item.getErrorCode(),
                item.getErrorMsg(),
                toInstant(item.getStartedAt()),
                toInstant(item.getFinishedAt()));
    }

    /** UTC 的 LocalDateTime → Instant（列本身存的就是 UTC，见 §3.2.1.1）。 */
    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
