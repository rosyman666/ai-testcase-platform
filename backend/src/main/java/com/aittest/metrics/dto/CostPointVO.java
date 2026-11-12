package com.aittest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #46 GET /api/metrics/cost 单点（docs/07 §3.2）。
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostPointVO {

    /** task 视角是 task_id 字符串；day 视角是 {@code yyyy-MM-dd}；model 视角是模型名。 */
    private String key;

    /** 仅 {@code groupBy=task} 时由 {@code gen_task.task_no} 提供，其余视角为 null。 */
    private String taskNo;

    private long promptTokens;

    private long completionTokens;

    /** SUM(cost_micro)，微元。 */
    private long costMicro;

    /** 台账行数。 */
    private long callCount;
}
