package com.aittest.metrics.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #46 GET /api/metrics/cost 出参（docs/07 §3.2 / §4.6）。
 *
 * <p>成本严格 {@code SUM(llm_call_log.cost_micro)}：三视角各自求和与直接
 * {@code SELECT SUM(cost_micro)} 的差值必须为 0，不在读取侧按 {@code model_price} 现算，
 * 也不改 T04 的记账链路。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostVO {

    /** task / day / model。 */
    private String groupBy;

    private List<CostPointVO> points;

    /** 严格落库值（微元）。 */
    private long totalCostMicro;

    private long totalPromptTokens;

    private long totalCompletionTokens;

    /** totalCostMicro / max(acceptedCaseCount, 1)。 */
    private long unitCostMicro;

    /** ACCEPTED + EDITED（与采纳率分子同形）。 */
    private long acceptedCaseCount;

    /** 成本 0 的三成因标注。 */
    private List<CostZeroReason> costZeroReasons;
}
