package com.aittest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #47 GET /api/metrics/quality 出参（docs/07 §3.2 / §4.7 / §6.10）。
 *
 * <p>三项必交付指标：首次通过率、解析失败率、{@code p50}/{@code p95} of
 * {@code gen_task_item.latency_ms}（<b>不用 avg</b>，长尾会骗人）；
 * {@code generateSuccessRate} 是同数据源附带项。数据源仅
 * {@code gen_task_item} + {@code llm_call_log}，不依赖 T10。</p>
 *
 * <p>按 {@code GenTaskItem.degraded} 拆分采纳率（⑤-9）本批不纳入（T11-P2-1 延后）。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityVO {

    /** count(attempts=1 AND status='SUCCESS') / totalItems。 */
    private double firstPassRate;

    /** count(status='SUCCESS') / totalItems。 */
    private double generateSuccessRate;

    /** count(llm_call_log.error_code LIKE 'E_LLM_PARSE%') / totalCalls。 */
    private double parseFailRate;

    private long p50Ms;

    private long p95Ms;

    private long totalItems;

    private long totalCalls;
}
