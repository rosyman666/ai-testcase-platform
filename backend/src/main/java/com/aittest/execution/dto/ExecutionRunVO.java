package com.aittest.execution.dto;

import com.aittest.execution.domain.ExecutionRun;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 执行批次列表项（{@code GET /api/executions}）。
 *
 * <p><b>{@code mappedRate} 是 0~1 的比例，不是百分数</b>：库里只存 {@code mapped_count} 与
 * {@code total_count} 两个事实，比率是<b>读出来的派生值</b>。存比率的坏处是
 * {@code total_count} 一旦修正（比如加了去重），历史比率全部失真且无法回算。
 * 前端展示 {@code 75.0%} 时自己乘 100，避免"有人当成百分数又乘一次"的经典错误。</p>
 *
 * <p><b>为什么 {@code mappedRate} 用 {@code BigDecimal} 而不是 {@code double}</b>：
 * 它与 {@code TestCase.confidence} 同一口径——{@code double} 会把 0.75 变成
 * 0.7499999999999999，页面上显示 74.99% 而实际映射率是 75%，这种"差一点点"的数字
 * 在验收比对时无法解释。固定 4 位小数，够精确也不会有浮点尾巴。</p>
 *
 * @param id           主键
 * @param runNo        批次单号（{@code R+yyyyMMdd+6位序号}）
 * @param taskId       所属任务主键（可能为 null：任务被删后 {@code ON DELETE SET NULL}）
 * @param taskNo       所属任务单号；任务不存在时 <b>null</b>（不是空串，前端据此显示"—"）
 * @param name         批次名称
 * @param sourceType   来源格式编码（JUNIT_XML / CSV）
 * @param fileName     原始文件名
 * @param totalCount   结果总数
 * @param passedCount  通过数
 * @param failedCount  失败数
 * @param skippedCount 跳过数
 * @param mappedCount  映射到用例的结果条数
 * @param mappedRate   映射率（0~1，4 位小数；{@code totalCount=0} 时为 0）
 * @param durationMs   批次总耗时（毫秒）
 * @param createdAt    创建时间（UTC）
 * @author 寇豆码
 */
public record ExecutionRunVO(Long id,
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
                             LocalDateTime createdAt) {

    /** 映射率保留位数。 */
    private static final int RATE_SCALE = 4;

    /**
     * 实体 → 出参。
     *
     * <p><b>{@code taskId} 是 {@code Long}、对外是字符串</b>：{@code JacksonConfig} 全局把
     * {@code Long} 序列化成 String（与 {@code TestCaseVO.id} 同一约定），所以前端 TS 里
     * 声明的 {@code string} 拿到的就是字符串。这里<b>不</b>在 record 里写 {@code String}——
     * 那要在每次装配时手动 {@code String.valueOf}，多一层容易漏掉的转换。</p>
     *
     * @param entity 批次实体（可为 null，返回 null）
     * @param taskNo 任务单号（任务已删/不存在为 null）
     * @return 出参
     */
    public static ExecutionRunVO from(ExecutionRun entity, String taskNo) {
        if (entity == null) {
            return null;
        }
        int total = safeInt(entity.getTotalCount());
        int mapped = safeInt(entity.getMappedCount());
        return new ExecutionRunVO(entity.getId(), entity.getRunNo(), entity.getTaskId(), taskNo,
                entity.getName(), entity.getSourceType(), entity.getFileName(), total,
                safeInt(entity.getPassedCount()), safeInt(entity.getFailedCount()),
                safeInt(entity.getSkippedCount()), mapped, rateOf(mapped, total),
                entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
                entity.getCreatedAt());
    }

    /**
     * 计算映射率。
     *
     * <p>{@code totalCount=0} 时返回 0 而不是抛 {@code ArithmeticException}：
     * 0/0 在"分母为零"的语义下最合理的展示是 0（前端配一条"本批次没有结果"的文案），
     * 而不是让整个列表页 500。</p>
     *
     * @param mapped 已映射条数
     * @param total  总条数
     * @return 0~1 的比例，4 位小数
     */
    public static BigDecimal rateOf(int mapped, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(mapped)
                .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 整数列兜底（DB 为 NOT NULL DEFAULT 0，但实体字段是 {@code Integer}，可能为 null）。
     *
     * @param value 原值
     * @return 非空整数
     */
    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
