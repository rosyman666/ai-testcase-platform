package com.aittest.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.aittest.common.BizException;
import com.aittest.metrics.dto.AcceptancePointVO;
import com.aittest.metrics.dto.MetricsQuery;
import com.aittest.metrics.dto.OverviewVO;
import com.aittest.metrics.dto.QualityVO;
import com.aittest.metrics.dto.RejectReasonPointVO;
import com.aittest.metrics.mapper.MetricsMapper;
import com.aittest.metrics.service.CostService;
import com.aittest.metrics.service.MetricsService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T11 #43/#44/#45/#47 口径单测（docs/07 §8 T03 完成判据 ②⑤⑥⑦）。
 *
 * <p><b>fixture 纪律</b>：本类的数据全部是内存 Map，仅用于验收/演示，不作为产品数据入库；
 * 成本样本含 {@code costMicro != 0} 行，时间窗用 {@link MetricsQuery#parse} 的默认「近 7 天」。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock
    private MetricsMapper metricsMapper;

    @Mock
    private CostService costService;

    private MetricsService service;

    @BeforeEach
    void setUp() {
        service = new MetricsService(metricsMapper, costService);
    }

    @Test
    @DisplayName("fixture 3/1/2/4 → accept=4/6、edited=1/6、reject=2/6、strict=3/6，4 条 DRAFT 不进任何比率")
    void acceptanceRatesUseReviewedDenominator() {
        when(metricsMapper.selectCaseCounts(any())).thenReturn(counts(10, 6, 4, 3, 1, 2));
        when(costService.callTotals(any())).thenReturn(totals(1000, 500, 200, 8, 1));
        when(costService.sumDirect(any())).thenReturn(200L);
        when(metricsMapper.selectLatencies(any())).thenReturn(List.of(10, 20, 30, 40, 50));

        OverviewVO vo = service.overview(defaultQuery());

        assertThat(vo.getGeneratedCount()).isEqualTo(10);
        assertThat(vo.getReviewedCount()).isEqualTo(6);
        assertThat(vo.getDraftCount()).isEqualTo(4);
        assertThat(vo.getAcceptRate()).isEqualTo(4.0 / 6.0);
        assertThat(vo.getEditedRate()).isEqualTo(1.0 / 6.0);
        assertThat(vo.getRejectRate()).isEqualTo(2.0 / 6.0);
        assertThat(vo.getStrictAcceptRate()).isEqualTo(3.0 / 6.0);
        assertThat(vo.getAcceptRate() + vo.getRejectRate()).isEqualTo(1.0);
        assertThat(vo.isHasReviewData()).isTrue();
        assertThat(vo.getTotalCostMicro()).isEqualTo(200);
        assertThat(vo.getUnitCostMicro()).isEqualTo(50);
        assertThat(vo.getP50Ms()).isEqualTo(30);
        assertThat(vo.getP95Ms()).isEqualTo(50);
        assertThat(vo.getCostZeroReasons()).isEmpty();
    }

    @Test
    @DisplayName("分母 0 → 所有比率返回数值 0、hasReviewData=false，不 NaN / 不 null / 不抛异常")
    void zeroDenominatorReturnsNumericZero() {
        when(metricsMapper.selectCaseCounts(any())).thenReturn(counts(4, 0, 4, 0, 0, 0));
        when(costService.callTotals(any())).thenReturn(totals(0, 0, 0, 0, 0));
        when(costService.sumDirect(any())).thenReturn(0L);
        when(metricsMapper.selectLatencies(any())).thenReturn(List.of());

        OverviewVO vo = service.overview(defaultQuery());

        assertThat(vo.getAcceptRate()).isZero();
        assertThat(vo.getEditedRate()).isZero();
        assertThat(vo.getRejectRate()).isZero();
        assertThat(vo.getStrictAcceptRate()).isZero();
        assertThat(vo.getUnitCostMicro()).isZero();
        assertThat(vo.getP50Ms()).isZero();
        assertThat(vo.getP95Ms()).isZero();
        assertThat(vo.isHasReviewData()).isFalse();
        assertThat(Double.isNaN(vo.getAcceptRate())).isFalse();
        assertThat(MetricsService.ratio(0, 0)).isZero();
        assertThat(MetricsService.ratio(1, 0)).isZero();
    }

    @Test
    @DisplayName("#44 分母 0 的点比率 0 + hasReviewData=false，前端不得渲染「0%」")
    void acceptancePointWithNoReviewData() {
        Map<String, Object> row = new HashMap<>();
        row.put("dayKey", "2026-11-12");
        row.put("generatedCount", 5L);
        row.put("reviewedCount", 0L);
        row.put("acceptedCount", 0L);
        row.put("editedCount", 0L);
        row.put("rejectedCount", 0L);
        when(metricsMapper.selectAcceptanceByDay(any())).thenReturn(List.of(row));

        List<AcceptancePointVO> points = service.acceptance(defaultQuery());

        assertThat(points).hasSize(1);
        assertThat(points.get(0).getKey()).isEqualTo("2026-11-12");
        assertThat(points.get(0).getAcceptRate()).isZero();
        assertThat(points.get(0).isHasReviewData()).isFalse();
    }

    @Test
    @DisplayName("#45 未知码归 OTHER 由 SQL 保证；0 行返回 []（不是 null）")
    void rejectReasonsEmptyAndOther() {
        when(metricsMapper.selectRejectReasons(any())).thenReturn(List.of());
        assertThat(service.rejectReasons(defaultQuery())).isEmpty();

        Map<String, Object> other = new HashMap<>();
        other.put("code", "OTHER");
        other.put("label", "其他原因");
        other.put("cnt", 2L);
        Map<String, Object> known = new HashMap<>();
        known.put("code", "CONTENT_ERROR");
        known.put("label", "内容错误");
        known.put("cnt", 3L);

        when(metricsMapper.selectRejectReasons(any())).thenReturn(List.of(other, known));
        List<RejectReasonPointVO> points = service.rejectReasons(defaultQuery());
        assertThat(points).hasSize(2);
        assertThat(points.get(0).getCode()).isEqualTo("OTHER");
        assertThat(points.get(0).getRatio()).isEqualTo(2.0 / 5.0);
        assertThat(points.get(1).getRatio()).isEqualTo(3.0 / 5.0);
    }

    @Test
    @DisplayName("#47 三项齐全：首次通过率 / 解析失败率 / p50·p95（不用 avg）")
    void qualityIncludesThreeRequiredMetrics() {
        Map<String, Object> items = new HashMap<>();
        items.put("totalItems", 10L);
        items.put("firstPassCount", 7L);
        items.put("successCount", 9L);
        when(metricsMapper.selectQuality(any())).thenReturn(items);
        when(costService.callTotals(any())).thenReturn(totals(1000, 500, 200, 20, 2));
        when(metricsMapper.selectLatencies(any())).thenReturn(List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 1000));

        QualityVO vo = service.quality(defaultQuery());

        assertThat(vo.getFirstPassRate()).isEqualTo(7.0 / 10.0);
        assertThat(vo.getGenerateSuccessRate()).isEqualTo(9.0 / 10.0);
        assertThat(vo.getParseFailRate()).isEqualTo(2.0 / 20.0);
        assertThat(vo.getP50Ms()).isEqualTo(50);
        assertThat(vo.getP95Ms()).isEqualTo(1000);
        assertThat(vo.getTotalItems()).isEqualTo(10);
        assertThat(vo.getTotalCalls()).isEqualTo(20);
    }

    @Test
    @DisplayName("p50/p95 用 nearest-rank 排序；空集返回 0；不用 avg")
    void percentileUsesNearestRank() {
        assertThat(service.percentile(List.of(50, 10, 30, 20, 40), 0.5)).isEqualTo(30);
        assertThat(service.percentile(List.of(50, 10, 30, 20, 40), 0.95)).isEqualTo(50);
        assertThat(service.percentile(List.of(), 0.5)).isZero();
        assertThat(service.percentile(null, 0.95)).isZero();
        assertThat(service.percentile(java.util.Arrays.asList(10, null, 20), 0.5)).isEqualTo(10);
    }

    @Test
    @DisplayName("from/to：默认近 7 天、固定 +8 偏移、左闭右开、to<from → 40001、格式错 → 40001")
    void timeWindowContract() {
        MetricsQuery defaults = MetricsQuery.parse(null, null, null, null, null);
        assertThat(defaults.getFrom()).isEqualTo(defaults.getTo().minusDays(6));
        assertThat(defaults.getFromUtc())
                .isEqualTo(defaults.getFrom().atStartOfDay().minusHours(8));
        assertThat(defaults.getToUtcExclusive())
                .isEqualTo(defaults.getTo().plusDays(1).atStartOfDay().minusHours(8));
        assertThat(MetricsQuery.SHANGHAI_OFFSET_HOURS).isEqualTo(8);

        LocalDate from = LocalDate.of(2026, 11, 12);
        MetricsQuery explicit = MetricsQuery.parse("2026-11-12", "2026-11-13", null, null, null);
        assertThat(explicit.getFrom()).isEqualTo(from);
        assertThat(explicit.getFromUtc()).isEqualTo(from.atStartOfDay().minusHours(8));
        assertThat(explicit.getToUtcExclusive())
                .isEqualTo(LocalDate.of(2026, 11, 14).atStartOfDay().minusHours(8));

        assertThat(catchCode(() -> MetricsQuery.parse("2026-11-13", "2026-11-12", null, null, null)))
                .isEqualTo(40001);
        assertThat(catchCode(() -> MetricsQuery.parse("2026/11/12", null, null, null, null)))
                .isEqualTo(40001);
        assertThat(catchCode(() -> MetricsQuery.parse(null, "abc", null, null, null)))
                .isEqualTo(40001);
    }

    @Test
    @DisplayName("groupBy 白名单：本批只放行 day / task,day,model；template、category 不在白名单")
    void groupByWhiteList() {
        assertThat(MetricsQuery.parse(null, null, null, null, "day")
                .requireGroupBy("day")).isEqualTo("day");
        assertThat(MetricsQuery.parse(null, null, null, null, "model")
                .requireGroupBy("task,day,model")).isEqualTo("model");
        assertThat(catchCode(() -> MetricsQuery.parse(null, null, null, null, "template")
                .requireGroupBy("day"))).isEqualTo(40001);
        assertThat(catchCode(() -> MetricsQuery.parse(null, null, null, null, "category")
                .requireGroupBy("task,day,model"))).isEqualTo(40001);
    }

    @Test
    @DisplayName("MetricsMapper.xml：OTHER 归并由 SQL 保证、reason_text 不参与、零软删条件、零 sql_mode 绕过")
    void mapperXmlContract() throws IOException {
        String xml = readMapperXml();
        assertThat(xml).contains("COALESCE(r.code, 'OTHER')");
        assertThat(xml).contains("ANY_VALUE(");
        assertThat(xml).contains("INTERVAL 8 HOUR");
        assertThat(xml).contains("cr.action = 'REJECT'");
        assertThat(xml).contains("LIKE 'E_LLM_PARSE%'");
        assertThat(xml).doesNotContain("reason_text");
        assertThat(xml).doesNotContain("deleted");
        assertThat(xml).doesNotContain("sql_mode");
        assertThat(xml).doesNotContain("${");
        assertThat(xml).contains("<if test=\"q.docId != null\">");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static MetricsQuery defaultQuery() {
        return MetricsQuery.parse(null, null, null, null, null);
    }

    private static Map<String, Object> counts(long generated, long reviewed, long draft,
                                               long accepted, long edited, long rejected) {
        Map<String, Object> map = new HashMap<>();
        map.put("generatedCount", generated);
        map.put("reviewedCount", reviewed);
        map.put("draftCount", draft);
        map.put("acceptedCount", accepted);
        map.put("editedCount", edited);
        map.put("rejectedCount", rejected);
        return map;
    }

    private static Map<String, Object> totals(long prompt, long completion, long cost,
                                              long calls, long parseFail) {
        Map<String, Object> map = new HashMap<>();
        map.put("promptTokens", prompt);
        map.put("completionTokens", completion);
        map.put("costMicro", cost);
        map.put("callCount", calls);
        map.put("parseFailCount", parseFail);
        return map;
    }

    private static String readMapperXml() throws IOException {
        try (InputStream in = MetricsServiceTest.class.getResourceAsStream("/mapper/MetricsMapper.xml")) {
            assertThat(in).as("MetricsMapper.xml 必须在 classpath 上").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int catchCode(Runnable action) {
        try {
            action.run();
            return -1;
        } catch (BizException ex) {
            return ex.getErrorCode().getCode();
        }
    }
}
