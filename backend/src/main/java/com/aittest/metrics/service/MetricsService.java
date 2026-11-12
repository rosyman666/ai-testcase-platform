package com.aittest.metrics.service;

import static com.aittest.metrics.service.CostService.longOf;

import com.aittest.metrics.dto.AcceptancePointVO;
import com.aittest.metrics.dto.CostZeroReason;
import com.aittest.metrics.dto.MetricsQuery;
import com.aittest.metrics.dto.OverviewVO;
import com.aittest.metrics.dto.QualityVO;
import com.aittest.metrics.dto.RejectReasonPointVO;
import com.aittest.metrics.mapper.MetricsMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * #43 / #44 / #45 / #47 的计数 → 应用层比率 → VO 组装（docs/07 §6.3 / §6.7 / §6.8）。
 *
 * <p><b>比率一律在 Java 侧算</b>：SQL 里写 {@code a/NULLIF(b,0)} 在分母 0 时得 NULL，
 * 序列化后前端拿到 {@code null}，与「返回数值 0」的契约冲突（D-6）。
 * 分母 0 时 {@link #ratio(long, long)} 返回 {@code 0.0}，不抛异常、不返回 NaN。</p>
 *
 * <p><b>p50/p95 用应用层 nearest-rank</b>：{@code GROUP_CONCAT} 受
 * {@code group_concat_max_len=1024} 截断，长尾正好被切掉，与「不用 avg（长尾会骗人）」
 * 直接相反；MySQL 8 也没有 {@code percentile_cont}（D-7）。</p>
 *
 * @author 寇豆码
 */
@Component
public class MetricsService {

    private final MetricsMapper metricsMapper;
    private final CostService costService;

    public MetricsService(MetricsMapper metricsMapper, CostService costService) {
        this.metricsMapper = metricsMapper;
        this.costService = costService;
    }

    /**
     * #43 overview：6 卡片 + p50/p95 + 成本 0 成因。
     *
     * @param query 时间窗 + 可选过滤
     * @return OverviewVO
     */
    public OverviewVO overview(MetricsQuery query) {
        Map<String, Object> counts = metricsMapper.selectCaseCounts(query);
        long generated = longOf(counts, "generatedCount");
        long reviewed = longOf(counts, "reviewedCount");
        long draft = longOf(counts, "draftCount");
        long accepted = longOf(counts, "acceptedCount");
        long edited = longOf(counts, "editedCount");
        long rejected = longOf(counts, "rejectedCount");

        Map<String, Object> totals = costService.callTotals(query);
        long totalCost = costService.sumDirect(query);
        long promptTokens = longOf(totals, "promptTokens");
        long completionTokens = longOf(totals, "completionTokens");
        long acceptedCaseCount = accepted + edited;
        // 分母 0 → 数值 0（T11-P0-5 / §8 T03 完成判据 ⑤），与 CostService 同口径
        long unitCost = acceptedCaseCount == 0L ? 0L : totalCost / acceptedCaseCount;

        List<Integer> latencies = metricsMapper.selectLatencies(query);
        long p50 = percentile(latencies, 0.5);
        long p95 = percentile(latencies, 0.95);

        List<CostZeroReason> reasons =
                (totalCost == 0L && (promptTokens + completionTokens) != 0L)
                        ? costService.explainZero(query)
                        : List.of();

        return OverviewVO.builder()
                .generatedCount(generated)
                .reviewedCount(reviewed)
                .draftCount(draft)
                .acceptedCount(accepted)
                .editedCount(edited)
                .rejectedCount(rejected)
                .acceptRate(ratio(accepted + edited, reviewed))
                .editedRate(ratio(edited, reviewed))
                .rejectRate(ratio(rejected, reviewed))
                .strictAcceptRate(ratio(accepted, reviewed))
                .totalCostMicro(totalCost)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .unitCostMicro(unitCost)
                .p50Ms(p50)
                .p95Ms(p95)
                .costZeroReasons(reasons)
                .hasReviewData(reviewed > 0L)
                .build();
    }

    /**
     * #44 acceptance：本批只做 {@code day}，与 #43 同源。
     *
     * @param query 时间窗 + 可选过滤 + groupBy
     * @return 趋势点（可能为空列表）
     */
    public List<AcceptancePointVO> acceptance(MetricsQuery query) {
        query.requireGroupBy("day");
        List<Map<String, Object>> rows = metricsMapper.selectAcceptanceByDay(query);
        List<AcceptancePointVO> points = new ArrayList<>();
        if (rows == null) {
            return points;
        }
        for (Map<String, Object> row : rows) {
            long reviewed = longOf(row, "reviewedCount");
            long accepted = longOf(row, "acceptedCount");
            long edited = longOf(row, "editedCount");
            long rejected = longOf(row, "rejectedCount");
            points.add(AcceptancePointVO.builder()
                    .key(String.valueOf(row.get("dayKey")))
                    .generatedCount(longOf(row, "generatedCount"))
                    .reviewedCount(reviewed)
                    .acceptedCount(accepted)
                    .editedCount(edited)
                    .rejectedCount(rejected)
                    .acceptRate(ratio(accepted + edited, reviewed))
                    .editedRate(ratio(edited, reviewed))
                    .rejectRate(ratio(rejected, reviewed))
                    .hasReviewData(reviewed > 0L)
                    .build());
        }
        return points;
    }

    /**
     * #45 reject-reasons：只按字典码聚合，0 行返回 {@code []}（不是 null）。
     *
     * @param query 时间窗 + 可选过滤
     * @return 驳回原因分布
     */
    public List<RejectReasonPointVO> rejectReasons(MetricsQuery query) {
        List<Map<String, Object>> rows = metricsMapper.selectRejectReasons(query);
        List<RejectReasonPointVO> points = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return points;
        }
        long total = 0L;
        for (Map<String, Object> row : rows) {
            total += longOf(row, "cnt");
        }
        for (Map<String, Object> row : rows) {
            long count = longOf(row, "cnt");
            points.add(RejectReasonPointVO.builder()
                    .code(CostService.strOf(row, "code"))
                    .label(CostService.strOf(row, "label"))
                    .count(count)
                    .ratio(ratio(count, total))
                    .build());
        }
        return points;
    }

    /**
     * #47 quality：首次通过率 / 解析失败率 / p50·p95（不用 avg），不依赖 T10。
     *
     * @param query 时间窗 + 可选过滤 + groupBy
     * @return QualityVO
     */
    public QualityVO quality(MetricsQuery query) {
        query.requireGroupBy("day");
        Map<String, Object> items = metricsMapper.selectQuality(query);
        long totalItems = longOf(items, "totalItems");
        long firstPass = longOf(items, "firstPassCount");
        long success = longOf(items, "successCount");

        Map<String, Object> totals = costService.callTotals(query);
        long totalCalls = longOf(totals, "callCount");
        long parseFail = longOf(totals, "parseFailCount");

        List<Integer> latencies = metricsMapper.selectLatencies(query);
        return QualityVO.builder()
                .firstPassRate(ratio(firstPass, totalItems))
                .generateSuccessRate(ratio(success, totalItems))
                .parseFailRate(ratio(parseFail, totalCalls))
                .p50Ms(percentile(latencies, 0.5))
                .p95Ms(percentile(latencies, 0.95))
                .totalItems(totalItems)
                .totalCalls(totalCalls)
                .build();
    }

    /**
     * 应用层比率：分母 0 → 数值 {@code 0}（不 NaN、不 null、不抛异常）。
     *
     * @param numerator 分子
     * @param denominator 分母
     * @return 0~1 的 double
     */
    public static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0 : numerator / (double) denominator;
    }

    /**
     * nearest-rank 分位数：排序后取第 {@code ceil(p*N)} 位；空集返回 0。
     *
     * @param values 耗时样本（可含 null）
     * @param p 分位（0~1）
     * @return 分位值（毫秒）
     */
    public long percentile(List<Integer> values, double p) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        List<Integer> sorted = new ArrayList<>();
        for (Integer value : values) {
            if (value != null) {
                sorted.add(value);
            }
        }
        if (sorted.isEmpty()) {
            return 0L;
        }
        sorted.sort(Integer::compareTo);
        int rank = (int) Math.ceil(p * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index).longValue();
    }
}
