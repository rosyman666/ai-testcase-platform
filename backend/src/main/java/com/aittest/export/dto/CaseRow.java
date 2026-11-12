package com.aittest.export.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 导出快照的一行用例（docs/07 §3.2）。
 *
 * <p><b>刻意不是 {@code TestCase} 实体</b>：快照是「某一时刻、某一次查询」的产物，
 * 与实体脱钩才能证明「统计 sheet 与用例 sheet 来自同一批数据、没有二次查库」。
 * 也避免把 {@code id / contentHash / updatedAt} 这些不该出现在 xlsx 里的列带出去。</p>
 *
 * <p>{@code steps} 保持 JSON 数组字符串原样（DDL 定义如此），不做解析；
 * {@code confidence} 保留 {@link BigDecimal}，避免浮点尾差在表格里变成 0.499。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"steps", "precondition", "expected"})
public class CaseRow {

    /** 同一 item 内的用例序号。 */
    private Integer seqNo;

    /** 用例标题。 */
    private String title;

    /** 前置条件。 */
    private String precondition;

    /** 测试步骤（JSON 数组字符串，原样导出）。 */
    private String steps;

    /** 预期结果。 */
    private String expected;

    /** 优先级 P0/P1/P2/P3。 */
    private String priority;

    /** 用例类型。 */
    private String caseType;

    /** 评审状态。 */
    private String status;

    /** 模型自评置信度。 */
    private BigDecimal confidence;

    /** 生成时使用的模型名。 */
    private String model;

    /** 生成时使用的提示词版本。 */
    private String promptVersion;

    /** 创建时间（UTC）。 */
    private LocalDateTime createdAt;
}
