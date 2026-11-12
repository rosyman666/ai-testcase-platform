package com.aittest.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * #45 GET /api/metrics/reject-reasons 单点（docs/07 §3.2 / §4.5）。
 *
 * <p>只按 {@code reject_reason.code} 聚合；未知 / NULL / 已停用字典码归 {@code OTHER}；
 * {@code reason_text} 永不参与聚合。0 行返回空数组 {@code []}（不是 null），
 * 前端 D 类空态「暂无驳回记录」，不画空饼图。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RejectReasonPointVO {

    /** 字典码（归一后）。 */
    private String code;

    /** 中文文案（仅展示）。 */
    private String label;

    /** 该码下的驳回记录数。 */
    private long count;

    /** count / 全部驳回数；分母 0 返回 0。 */
    private double ratio;
}
