package com.aittest.metrics.service;

import com.aittest.aicore.log.ModelPrice;
import com.aittest.aicore.log.ModelPriceMapper;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.metrics.dto.CostPointVO;
import com.aittest.metrics.dto.CostVO;
import com.aittest.metrics.dto.CostZeroReason;
import com.aittest.metrics.dto.MetricsQuery;
import com.aittest.metrics.mapper.MetricsMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * #46 成本聚合与「成本 0」三成因归因（docs/07 §6.6 / D-5）。
 *
 * <p><b>成本严格 {@code SUM(llm_call_log.cost_micro)}</b>：不在读取侧按
 * {@code model_price} 现算，不改 T04 记账链路。{@link ModelPriceMapper} 只用于判断
 * 「某模型有没有启用中的单价行」以便标注 {@link CostZeroReason#MODEL_PRICE_MISSING}，
 * <b>不新建 {@code ModelPriceService}、不调用 {@code ModelPriceCache.priceOf()}</b>
 * （后者无单价时会打 WARN，标注路径不该有副作用）。</p>
 *
 * <p><b>差值严格 0</b>：三视角各自求和必须与 {@link #sumDirect} 相等；不一致说明
 * XML 的 FROM/WHERE 漂移了，直接报 50000 而不是把错数交给前端画 chart。</p>
 *
 * @author 寇豆码
 */
@Component
public class CostService {

    private final MetricsMapper metricsMapper;
    private final ModelPriceMapper modelPriceMapper;

    public CostService(MetricsMapper metricsMapper, ModelPriceMapper modelPriceMapper) {
        this.metricsMapper = metricsMapper;
        this.modelPriceMapper = modelPriceMapper;
    }

    /**
     * #46：按 task / day / model 三视角之一聚合成本。
     *
     * @param query 时间窗 + 可选 docId/taskId + groupBy
     * @return CostVO
     */
    public CostVO cost(MetricsQuery query) {
        String groupBy = query.requireGroupBy("task,day,model");
        List<Map<String, Object>> rows = switch (groupBy) {
            case "task" -> metricsMapper.selectCostByTask(query);
            case "day" -> metricsMapper.selectCostByDay(query);
            case "model" -> metricsMapper.selectCostByModel(query);
            default -> throw new BizException(ErrorCode.E_PARAM_INVALID,
                    "groupBy 含非法值：" + groupBy + "；可选值：task/day/model");
        };

        List<CostPointVO> points = new ArrayList<>();
        long pointsCostSum = 0L;
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                CostPointVO point = CostPointVO.builder()
                        .key(strOf(row, "pointKey"))
                        .taskNo(row.get("taskNo") == null ? null : strOf(row, "taskNo"))
                        .promptTokens(longOf(row, "promptTokens"))
                        .completionTokens(longOf(row, "completionTokens"))
                        .costMicro(longOf(row, "costMicro"))
                        .callCount(longOf(row, "callCount"))
                        .build();
                points.add(point);
                pointsCostSum += point.getCostMicro();
            }
        }

        long direct = sumDirect(query);
        if (pointsCostSum != direct) {
            throw new BizException(ErrorCode.E_INTERNAL,
                    "成本聚合口径不一致：三视角求和=" + pointsCostSum + "，直接 SUM=" + direct);
        }

        Map<String, Object> totals = callTotals(query);
        long promptTokens = longOf(totals, "promptTokens");
        long completionTokens = longOf(totals, "completionTokens");
        Map<String, Object> caseCounts = metricsMapper.selectCaseCounts(query);
        long acceptedCaseCount = longOf(caseCounts, "acceptedCount") + longOf(caseCounts, "editedCount");
        // 分母 0 → 数值 0（T11-P0-5 / §8 T03 完成判据 ⑤）：不能写成 totalCost/max(0,1)，
        // 那会把「没有已采纳用例」表达成「单用例成本 = 全部成本」，是误导性的非零值。
        long unitCostMicro = acceptedCaseCount == 0L ? 0L : direct / acceptedCaseCount;
        List<CostZeroReason> reasons =
                (direct == 0L && (promptTokens + completionTokens) != 0L)
                        ? explainZero(query)
                        : List.of();

        return CostVO.builder()
                .groupBy(groupBy)
                .points(points)
                .totalCostMicro(direct)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .unitCostMicro(unitCostMicro)
                .acceptedCaseCount(acceptedCaseCount)
                .costZeroReasons(reasons)
                .build();
    }

    /**
     * 直接对同一窗口 {@code SUM(cost_micro)}，作为三视角求和的校验基准。
     *
     * @param query 查询
     * @return 成本（微元），空集返回 0
     */
    public long sumDirect(MetricsQuery query) {
        Long value = metricsMapper.sumCostDirect(query);
        return value == null ? 0L : value;
    }

    /**
     * 台账总量（token / 成本 / 调用数 / 解析失败数），#43/#46/#47 共用同一条 SQL。
     *
     * @param query 查询
     * @return 聚合 Map（永不为 null）
     */
    public Map<String, Object> callTotals(MetricsQuery query) {
        Map<String, Object> totals = metricsMapper.selectCallTotals(query);
        return totals == null ? Map.of() : totals;
    }

    /**
     * 「成本 0」三成因归因（只在 {@code totalCostMicro == 0 && totalTokens != 0} 时调用）。
     *
     * <p>三成因：mock-model 单价 0/0、失败调用无 token、模型缺少单价行。
     * 后者是<b>配置缺口，非代码缺陷</b>，排查方向是环境变量或历史验收脚本传参。</p>
     *
     * @param query 查询
     * @return 命中的成因（固定顺序、去重，可能为空列表）
     */
    public List<CostZeroReason> explainZero(MetricsQuery query) {
        List<ModelPrice> prices = priceRows();
        Set<String> pricedModels = new HashSet<>();
        Set<String> zeroPriceModels = new HashSet<>();
        for (ModelPrice price : prices) {
            String model = price.getModel();
            if (model == null || model.isBlank()) {
                continue;
            }
            pricedModels.add(model);
            if (zero(price.getInputPriceMicro()) && zero(price.getOutputPriceMicro())) {
                zeroPriceModels.add(model);
            }
        }

        Set<CostZeroReason> reasons = new LinkedHashSet<>();
        List<Map<String, Object>> rows = metricsMapper.selectCostModelStatus(query);
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                String model = strOf(row, "model");
                String status = strOf(row, "status");
                long tokens = longOf(row, "tokens");
                long calls = longOf(row, "callCount");
                if (calls <= 0L) {
                    continue;
                }
                if ("mock-model".equals(model)
                        && "SUCCESS".equalsIgnoreCase(status)
                        && zeroPriceModels.contains(model)) {
                    reasons.add(CostZeroReason.MOCK_PRICE_ZERO);
                }
                if ("FAILED".equalsIgnoreCase(status) && tokens == 0L) {
                    reasons.add(CostZeroReason.FAILED_CALL_NO_TOKEN);
                }
                if (!model.isBlank() && !pricedModels.contains(model)) {
                    reasons.add(CostZeroReason.MODEL_PRICE_MISSING);
                }
            }
        }
        return new ArrayList<>(reasons);
    }

    /**
     * 有启用中单价行的模型集合（只读 {@code aicore/log/ModelPriceMapper}）。
     *
     * @return 模型名集合（可能为空）
     */
    public Set<String> priceModels() {
        Set<String> models = new HashSet<>();
        for (ModelPrice price : priceRows()) {
            if (price.getModel() != null && !price.getModel().isBlank()) {
                models.add(price.getModel());
            }
        }
        return models;
    }

    /**
     * 取全部启用中的单价行；mapper 返回 null 时退化为空列表。
     *
     * @return 单价行
     */
    private List<ModelPrice> priceRows() {
        List<ModelPrice> prices = modelPriceMapper.selectEnabledOrderByEffectiveFromDesc();
        return prices == null ? List.of() : prices;
    }

    /** 单价是否为 0（null 视为 0）。 */
    private static boolean zero(Integer price) {
        return price == null || price == 0;
    }

    /**
     * 从 {@code resultType=Map} 的聚合行里安全取 long（MySQL 的 SUM 可能返回 BigDecimal）。
     *
     * <p>抽成 public static 是为了让 {@link MetricsService} 复用同一套行映射，
     * 避免两个服务各写一份「Number → long」而在某个类型上分叉。</p>
     *
     * @param row 聚合行（可为 null）
     * @param key 列别名
     * @return 数值；缺失或非 Number 返回 0
     */
    public static long longOf(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 从聚合行里安全取字符串（列值为 null 时返回空串）。
     *
     * @param row 聚合行（可为 null）
     * @param key 列别名
     * @return 字符串，永不为 null
     */
    public static String strOf(Map<String, Object> row, String key) {
        if (row == null) {
            return "";
        }
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
