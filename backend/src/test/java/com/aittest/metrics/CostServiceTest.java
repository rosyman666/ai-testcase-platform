package com.aittest.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.aicore.log.ModelPrice;
import com.aittest.aicore.log.ModelPriceMapper;
import com.aittest.common.BizException;
import com.aittest.metrics.dto.CostPointVO;
import com.aittest.metrics.dto.CostVO;
import com.aittest.metrics.dto.CostZeroReason;
import com.aittest.metrics.dto.MetricsQuery;
import com.aittest.metrics.mapper.MetricsMapper;
import com.aittest.metrics.service.CostService;
import java.math.BigDecimal;
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
 * T11 #46 成本口径单测（docs/07 §8 T03 完成判据 ④⑤⑩ / §6.6）。
 *
 * <p><b>非 0 成本 fixture</b>：本类样例里 {@code costMicro = 120 / 80 / 200}，
 * 保证「三视角求和 == 直接 SUM」不是 {@code 0=0} 假绿（T11-P0-12 / D-46 同型）。
 * 全部是内存 Map，仅用于验收，不入库；时间窗用 {@link MetricsQuery#parse} 的默认近 7 天。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class CostServiceTest {

    @Mock
    private MetricsMapper metricsMapper;

    @Mock
    private ModelPriceMapper modelPriceMapper;

    private CostService service;

    @BeforeEach
    void setUp() {
        service = new CostService(metricsMapper, modelPriceMapper);
    }

    @Test
    @DisplayName("三视角求和与直接 SUM 差值严格 0，且样本含 cost_micro != 0")
    void threeViewsMatchDirectSum() {
        List<Map<String, Object>> rows = List.of(
                point("7", "T20261112-000001", 600L, 300L, 120L, 1L),
                point("8", "T20261112-000002", 400L, 200L, 80L, 1L));
        when(metricsMapper.selectCostByTask(any())).thenReturn(rows);
        when(metricsMapper.selectCostByDay(any())).thenReturn(rows);
        when(metricsMapper.selectCostByModel(any())).thenReturn(rows);
        when(metricsMapper.sumCostDirect(any())).thenReturn(200L);
        when(metricsMapper.selectCallTotals(any())).thenReturn(totals(1000L, 500L, 200L, 2L, 0L));
        when(metricsMapper.selectCaseCounts(any())).thenReturn(counts(10L, 6L, 4L, 3L, 1L, 2L));

        CostVO byTask = service.cost(query("task"));
        CostVO byDay = service.cost(query("day"));
        CostVO byModel = service.cost(query("model"));

        for (CostVO vo : List.of(byTask, byDay, byModel)) {
            assertThat(vo.getTotalCostMicro()).isEqualTo(200L);
            assertThat(vo.getPoints().stream().mapToLong(CostPointVO::getCostMicro).sum())
                    .isEqualTo(200L);
            assertThat(vo.getUnitCostMicro()).isEqualTo(50L);
            assertThat(vo.getAcceptedCaseCount()).isEqualTo(4L);
            assertThat(vo.getCostZeroReasons()).isEmpty();
        }
        assertThat(byTask.getGroupBy()).isEqualTo("task");
        assertThat(byTask.getPoints().get(0).getTaskNo()).isEqualTo("T20261112-000001");
        assertThat(byDay.getGroupBy()).isEqualTo("day");
        assertThat(byModel.getGroupBy()).isEqualTo("model");
        verify(metricsMapper).selectCostByDay(any());
        verify(metricsMapper).selectCostByModel(any());
    }

    @Test
    @DisplayName("单用例成本分母 0 → 数值 0，非 null / 非 NaN / 不抛异常")
    void unitCostWithZeroAcceptedCasesIsZero() {
        when(metricsMapper.selectCostByTask(any()))
                .thenReturn(List.of(point("7", "T1", 100L, 50L, 200L, 1L)));
        when(metricsMapper.sumCostDirect(any())).thenReturn(200L);
        when(metricsMapper.selectCallTotals(any())).thenReturn(totals(100L, 50L, 200L, 1L, 0L));
        when(metricsMapper.selectCaseCounts(any())).thenReturn(counts(2L, 0L, 2L, 0L, 0L, 0L));

        CostVO vo = service.cost(query(null));

        assertThat(vo.getAcceptedCaseCount()).isZero();
        assertThat(vo.getUnitCostMicro()).isZero();
        assertThat(vo.getTotalCostMicro()).isEqualTo(200L);
    }

    @Test
    @DisplayName("成本 0 三成因可判别；deepseek-flash 标为配置缺口而非代码缺陷")
    void explainZeroDetectsThreeReasons() {
        when(modelPriceMapper.selectEnabledOrderByEffectiveFromDesc()).thenReturn(List.of(
                price("mock-model", 0, 0),
                price("deepseek-chat", 2_000_000, 3_000_000)));
        Map<String, Object> mockSuccess = modelStatus("mock-model", "SUCCESS", 10L, 1L);
        Map<String, Object> failedNoToken = modelStatus("deepseek-chat", "FAILED", 0L, 1L);
        Map<String, Object> missingPrice = modelStatus("deepseek-flash", "SUCCESS", 10L, 1L);
        when(metricsMapper.selectCostModelStatus(any()))
                .thenReturn(List.of(mockSuccess, failedNoToken, missingPrice));

        List<CostZeroReason> reasons = service.explainZero(query(null));

        assertThat(reasons).containsExactly(
                CostZeroReason.MOCK_PRICE_ZERO,
                CostZeroReason.FAILED_CALL_NO_TOKEN,
                CostZeroReason.MODEL_PRICE_MISSING);
        assertThat(CostZeroReason.MODEL_PRICE_MISSING.getLabel()).contains("配置缺口");
        assertThat(service.priceModels()).contains("mock-model", "deepseek-chat")
                .doesNotContain("deepseek-flash");
    }

    @Test
    @DisplayName("成本 0 但 token 非 0 时 CostVO 显式带 costZeroReasons，不隐藏、不现算")
    void costVoAnnotatesZeroCost() {
        when(metricsMapper.selectCostByTask(any())).thenReturn(List.of(
                point("7", "T1", 10L, 5L, 0L, 1L)));
        when(metricsMapper.sumCostDirect(any())).thenReturn(0L);
        when(metricsMapper.selectCallTotals(any())).thenReturn(totals(10L, 5L, 0L, 1L, 0L));
        when(metricsMapper.selectCaseCounts(any())).thenReturn(counts(1L, 1L, 0L, 1L, 0L, 0L));
        when(metricsMapper.selectCostModelStatus(any()))
                .thenReturn(List.of(modelStatus("deepseek-flash", "SUCCESS", 15L, 1L)));
        when(modelPriceMapper.selectEnabledOrderByEffectiveFromDesc()).thenReturn(List.of());

        CostVO vo = service.cost(query(null));

        assertThat(vo.getTotalCostMicro()).isZero();
        assertThat(vo.getTotalPromptTokens()).isEqualTo(10L);
        assertThat(vo.getTotalCompletionTokens()).isEqualTo(5L);
        assertThat(vo.getCostZeroReasons()).contains(CostZeroReason.MODEL_PRICE_MISSING);
        assertThat(vo.getPoints().get(0).getCostMicro()).isZero();
    }

    @Test
    @DisplayName("非法 groupBy → 40001，且在任何查库之前")
    void illegalGroupByRejectedBeforeQuery() {
        BizException ex = catchBiz(() -> service.cost(query("template")));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40001);
        assertThat(ex.getMessage()).contains("template").contains("task/day/model");
        verifyNoInteractions(metricsMapper, modelPriceMapper);
    }

    @Test
    @DisplayName("差值不一致时抛 50000，绝不把错数交给前端画 chart")
    void mismatchedSumIsRejected() {
        when(metricsMapper.selectCostByTask(any()))
                .thenReturn(List.of(point("7", "T1", 10L, 5L, 199L, 1L)));
        when(metricsMapper.sumCostDirect(any())).thenReturn(200L);

        BizException ex = catchBiz(() -> service.cost(query("task")));
        assertThat(ex.getErrorCode().getCode()).isEqualTo(50000);
        assertThat(ex.getMessage()).contains("成本聚合口径不一致");
    }

    @Test
    @DisplayName("longOf 兼容 MySQL SUM 返回的 BigDecimal / Long / null")
    void longOfHandlesNumericTypes() {
        Map<String, Object> row = new HashMap<>();
        row.put("big", new BigDecimal("123"));
        row.put("long", 456L);
        row.put("text", "789");
        row.put("nullValue", null);
        assertThat(CostService.longOf(row, "big")).isEqualTo(123L);
        assertThat(CostService.longOf(row, "long")).isEqualTo(456L);
        assertThat(CostService.longOf(row, "text")).isEqualTo(789L);
        assertThat(CostService.longOf(row, "nullValue")).isZero();
        assertThat(CostService.longOf(null, "big")).isZero();
        assertThat(CostService.strOf(row, "nullValue")).isEmpty();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static MetricsQuery query(String groupBy) {
        return MetricsQuery.parse(null, null, null, null, groupBy);
    }

    private static Map<String, Object> point(String key, String taskNo, long prompt,
                                             long completion, long cost, long calls) {
        Map<String, Object> map = new HashMap<>();
        map.put("pointKey", key);
        map.put("taskNo", taskNo);
        map.put("promptTokens", prompt);
        map.put("completionTokens", completion);
        map.put("costMicro", cost);
        map.put("callCount", calls);
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

    private static Map<String, Object> modelStatus(String model, String status,
                                                   long tokens, long calls) {
        Map<String, Object> map = new HashMap<>();
        map.put("model", model);
        map.put("status", status);
        map.put("tokens", tokens);
        map.put("callCount", calls);
        return map;
    }

    private static ModelPrice price(String model, int input, int output) {
        ModelPrice price = new ModelPrice();
        price.setModel(model);
        price.setInputPriceMicro(input);
        price.setOutputPriceMicro(output);
        return price;
    }

    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
            throw new AssertionError("预期抛出 BizException，实际正常返回");
        } catch (BizException ex) {
            return ex;
        }
    }
}
