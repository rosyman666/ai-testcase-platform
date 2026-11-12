package com.aittest.metrics.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #43 GET /api/metrics/overview 出参（docs/07 §3.2 / §8.3）。
 *
 * <p><b>比率分母 = 已评审数 {@code ACCEPTED+EDITED+REJECTED}，不含 DRAFT</b>；
 * {@code reviewedCount == 0} 时所有比率返回数值 {@code 0}（不 NaN、不 null），
 * 并置 {@code hasReviewData=false} 供前端 B 类展示「暂无评审数据」。</p>
 *
 * <p><b>#43「失败率」= {@code rejectRate}</b>，中文标签「驳回率」，不引入第三个失败率。
 * {@code p50Ms}/{@code p95Ms} 并入本 VO 是防 T11-P1-1 被压缩时 §6 T11 ⑥ 漏项。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverviewVO {

    /** 生成用例数（含 DRAFT）。 */
    private long generatedCount;

    /** 已评审数，所有比率的分母基准。 */
    private long reviewedCount;

    private long draftCount;

    private long acceptedCount;

    private long editedCount;

    private long rejectedCount;

    /** (ACCEPTED + EDITED) / reviewedCount。 */
    private double acceptRate;

    /** EDITED / reviewedCount。 */
    private double editedRate;

    /** REJECTED / reviewedCount，中文标签「驳回率」。 */
    private double rejectRate;

    /** ACCEPTED / reviewedCount。 */
    private double strictAcceptRate;

    /** SUM(llm_call_log.cost_micro)，严格落库值（微元）。 */
    private long totalCostMicro;

    private long totalPromptTokens;

    private long totalCompletionTokens;

    /** totalCostMicro / max(acceptedCaseCount, 1)。 */
    private long unitCostMicro;

    /** 应用层 nearest-rank p50（毫秒）。 */
    private long p50Ms;

    /** 应用层 nearest-rank p95（毫秒）。 */
    private long p95Ms;

    /** 成本 0 的三成因标注。 */
    private List<CostZeroReason> costZeroReasons;

    /** reviewedCount > 0；false → 前端 B 类「暂无评审数据」。 */
    private boolean hasReviewData;
}
