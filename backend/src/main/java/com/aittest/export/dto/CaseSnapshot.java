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
 * 导出快照聚合根：一次查询得到的全部导出事实（docs/07 §3.2 / D-11）。
 *
 * <p><b>强约束</b>：{@link ExportStats} 必须由本对象在内存里算出来，
 * <b>禁止再查一次库</b>。两次查询之间若有人评审，统计 sheet 与用例 sheet 就会对不上，
 * 而且不会报错——这是最难在验收里发现的一类错。</p>
 *
 * <p>{@code statusCounts} 的键固定是 {@code CaseStatus} 四值，未导出的状态也保留 {@code 0}，
 * 供「统计」sheet 完整回显四值分布。</p>
 *
 * @author 寇豆码
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseSnapshot {

    /** 任务业务单号（与 {@code gen_task.task_no} 一致，用于文件名）。 */
    private String taskNo;

    /** 本批导出的用例行（顺序稳定，供 JUnit 的 {@code case_NNN} 编号）。 */
    private List<CaseRow> rows;

    /** 四值状态分布（含值为 0 的状态）。 */
    private Map<String, Integer> statusCounts;

    /** 导出时刻（UTC；统计 sheet 会标注时区）。 */
    private LocalDateTime exportedAt;

    /** 生效导出条件回显文本。 */
    private String criteria;

    /**
     * 本批用例条数（rows 永不为 null 且非空：空集在进入本对象前已抛 40003）。
     *
     * @return 用例条数
     */
    public int exportedCount() {
        return rows == null ? 0 : rows.size();
    }
}
