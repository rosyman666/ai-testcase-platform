package com.aittest.export.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 「统计」sheet 的 5 个字段（docs/07 §4.1 / D-11）。
 *
 * <p><b>只能由 {@link #fromSnapshot(CaseSnapshot)} 构造</b>，不提供任何查库入口。
 * 这里刻意**不放**采纳率 / 成本 / token / 驳回原因明细 / 测试点覆盖率——那是 T11
 * 看板的职责，混进导出统计会让两个模块的口径各自演化。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportStats {

    /** 导出时间（UTC，表头标注时区）。 */
    private LocalDateTime exportedAt;

    /** 任务业务单号。 */
    private String taskNo;

    /** 生效导出条件（statuses + taskId + docId）。 */
    private String criteria;

    /** 导出用例数。 */
    private Integer exportedCount;

    /** 状态分布（四值计数，未导的状态写 0）。 */
    private Map<String, Integer> statusCounts;

    /**
     * 从同一份快照在内存里组装统计（**禁止二次查库**）。
     *
     * @param snapshot 导出快照
     * @return 统计视图
     */
    public static ExportStats fromSnapshot(CaseSnapshot snapshot) {
        return ExportStats.builder()
                .exportedAt(snapshot.getExportedAt())
                .taskNo(snapshot.getTaskNo())
                .criteria(snapshot.getCriteria())
                .exportedCount(snapshot.exportedCount())
                .statusCounts(snapshot.getStatusCounts())
                .build();
    }

    /**
     * 把统计视图摊成「统计项 / 值」两列的 5 行，供 EasyExcel 写「统计」sheet。
     *
     * <p>顺序与 {@link com.aittest.export.support.ExcelColumnDef#STAT_FIELD_LABELS} 一致；
     * 状态分布用 {@code DRAFT=0; ACCEPTED=2; ...} 单格表达，保证四值（含 0）都出现。</p>
     *
     * @param labels 5 个统计项标签
     * @return 5 行数据
     */
    public List<List<Object>> toRows(List<String> labels) {
        String distribution = renderDistribution(statusCounts);
        return List.of(
                List.<Object>of(labels.get(0), exportedAt == null ? "" : exportedAt + " UTC"),
                List.<Object>of(labels.get(1), taskNo == null ? "" : taskNo),
                List.<Object>of(labels.get(2), criteria == null ? "" : criteria),
                List.<Object>of(labels.get(3), exportedCount == null ? 0 : exportedCount),
                List.<Object>of(labels.get(4), distribution));
    }

    /**
     * 按固定四值顺序渲染状态分布（缺失的状态写 0）。
     *
     * @param counts 状态计数
     * @return 形如 {@code DRAFT=0; ACCEPTED=2; EDITED=1; REJECTED=0}
     */
    private static String renderDistribution(Map<String, Integer> counts) {
        List<String> order = List.of("DRAFT", "ACCEPTED", "EDITED", "REJECTED");
        StringBuilder text = new StringBuilder();
        for (String status : order) {
            if (text.length() > 0) {
                text.append("; ");
            }
            int value = counts == null ? 0 : counts.getOrDefault(status, 0);
            text.append(status).append('=').append(value);
        }
        return text.toString();
    }
}
