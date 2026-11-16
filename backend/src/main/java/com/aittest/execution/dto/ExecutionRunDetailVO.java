package com.aittest.execution.dto;

import com.aittest.common.enums.ExecutionStatus;
import com.aittest.execution.domain.ExecutionRun;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行批次详情（{@code GET /api/executions/{id}}）：列表项 + 两个分布。
 *
 * <p><b>{@code statusCounts} 三值必须都出现，0 也保留</b>：前端用同一套柱状/环形图渲染，
 * 少一个值会让"这一批没有跳过"看起来像"这个功能不支持跳过"。这类"缺项"在截图评审里
 * 极难发现，所以由后端补齐（与 {@code CaseSnapshot#statusCounts} 四值补齐同一口径）。</p>
 *
 * <p><b>{@code categoryDistribution} 来自真实聚合 SQL</b>，不是硬编码空数组：
 * 导入时规则归因（以及随后的人工纠正）写进 {@code failure_category}，这里就有数据；
 * 一条失败都没归因时才返回空列表，那时空列表本身就是正确结论。</p>
 *
 * @param id                   主键
 * @param runNo                批次单号
 * @param taskId               所属任务主键（任务被删后为 null）
 * @param taskNo               所属任务单号（任务不存在时为 null）
 * @param name                 批次名称
 * @param sourceType           来源格式编码
 * @param fileName             原始文件名
 * @param totalCount           结果总数
 * @param passedCount          通过数
 * @param failedCount          失败数
 * @param skippedCount         跳过数
 * @param mappedCount          映射到用例的结果条数
 * @param mappedRate           映射率（0~1，4 位小数）
 * @param durationMs           批次总耗时（毫秒）
 * @param createdAt            创建时间（UTC）
 * @param statusCounts         三值状态分布（PASSED/FAILED/SKIPPED 恒出现）
 * @param categoryDistribution 失败分类分布（按条数倒序；本批通常为空）
 * @author 寇豆码
 */
public record ExecutionRunDetailVO(Long id,
                                   String runNo,
                                   Long taskId,
                                   String taskNo,
                                   String name,
                                   String sourceType,
                                   String fileName,
                                   Integer totalCount,
                                   Integer passedCount,
                                   Integer failedCount,
                                   Integer skippedCount,
                                   Integer mappedCount,
                                   BigDecimal mappedRate,
                                   Long durationMs,
                                   LocalDateTime createdAt,
                                   List<Point> statusCounts,
                                   List<CategoryPoint> categoryDistribution) {

    /**
     * 一个状态计数点。
     *
     * <p>{@code label} 取 {@link ExecutionStatus#label()}（不现查字典表）：三值是封闭枚举，
     * 文案与前端 {@code constants/status.ts} 的 {@code EXECUTION_STATUS} 保持一致，
     * 现查字典只会多一次 IO 并引入"字典被停用后标签变空"的可能。</p>
     *
     * @param status 状态编码（PASSED / FAILED / SKIPPED）
     * @param label  中文文案
     * @param count  条数（0 也保留）
     */
    public record Point(String status, String label, Integer count) {
    }

    /**
     * 一个失败分类计数点。
     *
     * @param category 分类字典码（{@code failure_category.code}）
     * @param label    中文文案（字典缺失时回落为码值）
     * @param count    条数
     */
    public record CategoryPoint(String category, String label, Integer count) {
    }

    /**
     * 实体 + 任务单号 + 两个分布 → 详情出参。
     *
     * @param entity               批次实体
     * @param taskNo               所属任务单号（任务不存在时为 null）
     * @param categoryDistribution 失败分类分布（可为 null，按空列表处理）
     * @return 详情；{@code entity} 为 null 时返回 null
     */
    public static ExecutionRunDetailVO from(ExecutionRun entity, String taskNo,
                                            List<CategoryPoint> categoryDistribution) {
        if (entity == null) {
            return null;
        }
        return new ExecutionRunDetailVO(entity.getId(), entity.getRunNo(), entity.getTaskId(), taskNo,
                entity.getName(), entity.getSourceType(), entity.getFileName(),
                safeInt(entity.getTotalCount()), safeInt(entity.getPassedCount()),
                safeInt(entity.getFailedCount()), safeInt(entity.getSkippedCount()),
                safeInt(entity.getMappedCount()),
                ExecutionRunVO.rateOf(safeInt(entity.getMappedCount()), safeInt(entity.getTotalCount())),
                entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
                entity.getCreatedAt(),
                statusCountsOf(entity),
                categoryDistribution == null ? List.of() : categoryDistribution);
    }

    /**
     * 由批次的四个计数列拼三值分布。
     *
     * <p><b>不再查一次聚合</b>：{@code execution_run} 上已经存着通过/失败/跳过三个数，
     * 它们是插入时由同一批明细算出来的。再 {@code GROUP BY} 一次明细表意味着同一事实有两个
     * 来源，两者一旦不一致（比如明细写入失败但批次已提交），页面上就会出现
     * "总数 100、三个分布加起来 99"这种没法解释的状态。</p>
     *
     * @param entity 批次实体
     * @return 恒为 3 个元素的列表（按枚举声明顺序）
     */
    private static List<Point> statusCountsOf(ExecutionRun entity) {
        List<Point> points = new ArrayList<>(ExecutionStatus.values().length);
        for (ExecutionStatus status : ExecutionStatus.values()) {
            points.add(new Point(status.code(), status.label(), countOf(entity, status)));
        }
        return points;
    }

    /**
     * 取某状态的计数。
     *
     * @param entity 批次实体
     * @param status 状态
     * @return 条数（缺失按 0）
     */
    private static int countOf(ExecutionRun entity, ExecutionStatus status) {
        return switch (status) {
            case PASSED -> safeInt(entity.getPassedCount());
            case FAILED -> safeInt(entity.getFailedCount());
            case SKIPPED -> safeInt(entity.getSkippedCount());
        };
    }

    /**
     * 整数列兜底。
     *
     * @param value 原值
     * @return 非空整数
     */
    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
