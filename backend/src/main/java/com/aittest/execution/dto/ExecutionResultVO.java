package com.aittest.execution.dto;

import com.aittest.common.enums.ExecutionStatus;
import com.aittest.execution.domain.ExecutionResult;
import java.time.LocalDateTime;

/**
 * 单条执行结果（{@code GET /api/executions/{id}/results}）。
 *
 * <p><b>{@code stackTrace} 只返回前 500 字</b>（截断处加省略号）：一个 JUnit 的
 * stack trace 动辄上万字符，而页面只展示前几屏。整段返回会让"一页 20 条明细"的响应
 * 变成几 MB——在弱网下就是几秒白屏，而<b>用户根本看不到后面那些字符</b>。
 * 完整堆栈仍在库里（{@code stack_trace MEDIUMTEXT}），需要时按 id 单独取。</p>
 *
 * <p><b>截断在 {@code from()} 里做，不在 SQL 里 {@code LEFT(stack_trace, 500)}</b>：
 * SQL 截断后"是否被截断"这个信息就没了，无法决定要不要加省略号；而且 SQL 层截断
 * 会让 {@code COUNT} 之外的任何复用（比如将来做堆栈关键词检索）拿到残缺数据。</p>
 *
 * @param id             主键
 * @param runId          所属批次
 * @param className      测试类全名
 * @param caseName       测试方法名
 * @param status         结果编码（PASSED/FAILED/SKIPPED）
 * @param durationMs     单条耗时（毫秒）
 * @param failureMessage 失败消息（完整；页面按需截断展示）
 * @param stackTrace     堆栈（<b>最多 500 字</b>，被截断时以省略号结尾）
 * @param caseId         映射到的用例（未映射为 null）
 * @param failureCategory 失败分类字典码（RULE 规则归因或 MANUAL 人工纠正后写入；
 *                        PASSED/SKIPPED 恒为 null）
 * @param failureLabel   失败分类中文文案（字典缺失或未归因时为 null）
 * @param triageSource   归因来源（RULE/LLM/MANUAL）。
 *                       <b>归因只存在于失败结果（FAILED）</b>；通过/跳过行没有归因来源，
 *                       出参恒为 {@code null}——DB 列因 {@code NOT NULL DEFAULT 'RULE'}
 *                       有兜底值，但那是存储细节，不是语义
 * @param createdAt      创建时间（UTC）
 * @author 寇豆码
 */
public record ExecutionResultVO(Long id,
                                Long runId,
                                String className,
                                String caseName,
                                String status,
                                Long durationMs,
                                String failureMessage,
                                String stackTrace,
                                Long caseId,
                                String failureCategory,
                                String failureLabel,
                                String triageSource,
                                LocalDateTime createdAt) {

    /** 堆栈返回给前端的最大字符数。 */
    public static final int STACK_TRACE_MAX = 500;

    /** 截断标记（一个字符，不占展示宽度）。 */
    private static final String ELLIPSIS = "…";

    /**
     * 实体 → 出参（会截断 {@code stackTrace}）。
     *
     * @param entity       明细实体（可为 null，返回 null）
     * @param failureLabel 失败分类中文文案（可为 null）
     * @return 出参
     */
    public static ExecutionResultVO from(ExecutionResult entity, String failureLabel) {
        if (entity == null) {
            return null;
        }
        return new ExecutionResultVO(entity.getId(), entity.getRunId(),
                nullToEmpty(entity.getClassName()), nullToEmpty(entity.getCaseName()),
                entity.getStatus(), entity.getDurationMs() == null ? 0L : entity.getDurationMs(),
                entity.getFailureMessage(), abbreviateStackTrace(entity.getStackTrace()),
                entity.getCaseId(), entity.getFailureCategory(), failureLabel,
                triageSourceOf(entity), entity.getCreatedAt());
    }

    /**
     * 归因来源：<b>只在 FAILED 行上透出</b>，其余状态一律 {@code null}。
     *
     * <p><b>为什么要在出参这层归一</b>：{@code execution_result.triage_source} 是
     * {@code NOT NULL DEFAULT 'RULE'}，通过/跳过的行插入时没有赋值，被数据库默认值补成
     * {@code 'RULE'}。若原样透出，得到的是两句互相打脸的话——"这两条根本没归因过"与
     * "来源=规则"；详情弹窗会渲染成「归因：未归因（规则）」，用户以为归因系统抽风。
     * 更实际的影响是按 {@code triage_source = 'RULE'} 统计"归因覆盖率"时会把通过的行
     * 也算进去，得到一个偏高且看起来合理的数。</p>
     *
     * <p><b>为什么不改库里那两列</b>：{@code markFailureCategory} 的守卫是
     * {@code AND triage_source <> 'MANUAL'}，把通过行的值改写或让列可空都会连带影响它；
     * 存量值保持原样，只在出参这一层表达真相。</p>
     *
     * <p><b>FAILED 行必须原样透出</b>（{@code RULE/LLM/MANUAL} 都不动）：一旦这里顺手
     * 按"有没有分类"再过滤一次，人工纠正过的行会退化成"—"，「归因来源」整列消失——
     * 整列消失比偶尔显示错更难被发现。</p>
     *
     * @param entity 明细实体（非 null）
     * @return FAILED 行的来源值；其余状态返回 {@code null}
     */
    private static String triageSourceOf(ExecutionResult entity) {
        if (!ExecutionStatus.FAILED.code().equals(entity.getStatus())) {
            return null;
        }
        return entity.getTriageSource();
    }

    /**
     * 截断堆栈到 {@link #STACK_TRACE_MAX} 字（被截断时补省略号）。
     *
     * @param stackTrace 原始堆栈（可为 null）
     * @return 截断后的堆栈；null 入参返回 null（"没有堆栈"与"堆栈是空串"在页面上含义不同）
     */
    public static String abbreviateStackTrace(String stackTrace) {
        if (stackTrace == null || stackTrace.length() <= STACK_TRACE_MAX) {
            return stackTrace;
        }
        return stackTrace.substring(0, STACK_TRACE_MAX) + ELLIPSIS;
    }

    /**
     * null 文本 → 空串（{@code class_name}/{@code case_name} 在 DDL 里是 NOT NULL DEFAULT ''，
     * 但 CSV 可能整列缺失，实体里就会出现 null）。
     *
     * @param value 原值
     * @return 非 null 文本
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
