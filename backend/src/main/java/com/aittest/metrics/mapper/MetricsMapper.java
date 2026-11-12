package com.aittest.metrics.mapper;

import com.aittest.metrics.dto.MetricsQuery;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * T11 指标聚合 SQL（{@code mapper/MetricsMapper.xml}）。
 *
 * <p><b>全部实时聚合、零写入</b>：{@code metric_snapshot} 表 DDL 明写「MVP 不物化」，
 * 本接口没有任何 INSERT/UPDATE 方法（D-8）。行结果用 {@code Map} 承接，沿用现有 4 个
 * mapper XML 的 {@code resultType} + 驼峰映射约定，不新建实体、零 {@code resultMap}。</p>
 *
 * <p><b>三视角是三条独立 select</b>：{@code groupBy} 由 Service 白名单分派，
 * 不用 {@code ${}} 拼 SQL；可选过滤 {@code docId}/{@code taskId} 才用 {@code <if>}。</p>
 *
 * @author 寇豆码
 */
@Mapper
public interface MetricsMapper {

    /** #43：四态计数（只返回计数，比率在应用层算，分母 0 → 0）。 */
    Map<String, Object> selectCaseCounts(@Param("q") MetricsQuery q);

    /** #44：按天采纳趋势（本批 groupBy 仅 day）。 */
    List<Map<String, Object>> selectAcceptanceByDay(@Param("q") MetricsQuery q);

    /** #45：按驳回原因字典码聚合；未知/NULL/停用归 OTHER；reason_text 不参与。 */
    List<Map<String, Object>> selectRejectReasons(@Param("q") MetricsQuery q);

    /** #46 groupBy=task。 */
    List<Map<String, Object>> selectCostByTask(@Param("q") MetricsQuery q);

    /** #46 groupBy=day。 */
    List<Map<String, Object>> selectCostByDay(@Param("q") MetricsQuery q);

    /** #46 groupBy=model。 */
    List<Map<String, Object>> selectCostByModel(@Param("q") MetricsQuery q);

    /** #47：gen_task_item 的首次通过 / 成功率计数。 */
    Map<String, Object> selectQuality(@Param("q") MetricsQuery q);

    /** #43/#47：item 耗时样本，应用层 nearest-rank 取 p50/p95。 */
    List<Integer> selectLatencies(@Param("q") MetricsQuery q);

    /** #46：直接 SUM(cost_micro)，用于校验三视角差值严格为 0。 */
    Long sumCostDirect(@Param("q") MetricsQuery q);

    /** 台账总量（token / 成本 / 调用数 / 解析失败数），供 #43/#46/#47 共用。 */
    Map<String, Object> selectCallTotals(@Param("q") MetricsQuery q);

    /** 成本 0 归因的辅助聚合：按 model + status 分组。 */
    List<Map<String, Object>> selectCostModelStatus(@Param("q") MetricsQuery q);
}
