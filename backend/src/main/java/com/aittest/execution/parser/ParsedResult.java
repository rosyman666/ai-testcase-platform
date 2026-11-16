package com.aittest.execution.parser;

/**
 * 一条解析出来的执行结果（<b>尚未落库</b>的中间形态）。
 *
 * <p><b>为什么不直接解析成实体 {@code ExecutionResult}</b>：实体承载的是"库里那一行"，
 * 含 {@code runId} / {@code caseId} / {@code triageSource} 等需要<b>后续步骤</b>才能确定的字段。
 * 解析器只知道文件里写了什么，让它去填 {@code runId} 只能填 null，然后由 service 覆盖——
 * 一个"看起来能用但一半字段是 null"的对象最容易在后续合并中被误当成完整实体。
 * 本 record 只装文件里真实存在的事实，映射与落库由 service 负责。</p>
 *
 * <p><b>{@code status} 用 String 而不是 {@code ExecutionStatus}</b>：解析器负责把
 * {@code failure/error/skipped} 这类格式差异归一成 {@code PASSED/FAILED/SKIPPED}，
 * 但"校验并用码值存库"是 service 的职责。用 String 让不合法的取值可以在解析阶段
 * 抛带行号的 44001（CSV 场景），而不是在写库时变成 {@code IllegalArgumentException} → 50000。</p>
 *
 * <p><b>{@code failureType} 是归因的前提</b>（T10-3）：JUnit XML 的
 * {@code <failure type="java.lang.AssertionError">} 里 {@code type} 才是可靠的异常全限定名，
 * 只看文案关键词在真实报告上会大面积落到 {@code UNKNOWN}。它<b>没有独立的列</b>：
 * 解析结束时已被 {@link FailureTypes#withHeader} 前置进 {@code stackTrace} 首行，
 * 因此落库后规则只需读堆栈首行即可，不依赖本中间态是否被正确传递。</p>
 *
 * @param className      测试类全名（{@code com.aittest.generated.GeneratedCase_001}；可能为空串）
 * @param caseName       测试方法名（{@code case_001}；可能为空串）
 * @param status         {@code PASSED} / {@code FAILED} / {@code SKIPPED}
 * @param durationMs     单条耗时（毫秒；无法解析按 0）
 * @param failureMessage 失败断言消息（无则空串，<b>不为 null</b>）
 * @param stackTrace     堆栈（无则空串，<b>不为 null</b>；已确保以 {@code failureType} 开头）
 * @param failureType    异常全限定名（无则 {@code null}；<b>为 null 是正常情况</b>）
 * @author 寇豆码
 */
public record ParsedResult(String className,
                           String caseName,
                           String status,
                           long durationMs,
                           String failureMessage,
                           String stackTrace,
                           String failureType) {
}
