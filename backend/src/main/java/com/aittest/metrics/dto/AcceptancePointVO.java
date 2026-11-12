package com.aittest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #44 GET /api/metrics/acceptance 单点（docs/07 §3.2 / §4.4）。
 *
 * <p>口径与 {@link OverviewVO} 同源；分母 0 的点比率返回 {@code 0} 且
 * {@code hasReviewData=false}，前端<b>不渲染「0%」</b>（把「人还没评审」误读成
 * 「模型全错了」与 §8.3 立场相反）。本批 {@code groupBy} 只实现 {@code day}。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcceptancePointVO {

    /** day 视角格式 {@code yyyy-MM-dd}。 */
    private String key;

    private long generatedCount;

    private long reviewedCount;

    private long acceptedCount;

    private long editedCount;

    private long rejectedCount;

    private double acceptRate;

    private double editedRate;

    private double rejectRate;

    private boolean hasReviewData;
}
