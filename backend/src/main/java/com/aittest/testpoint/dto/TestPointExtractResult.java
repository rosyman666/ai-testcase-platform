package com.aittest.testpoint.dto;

/**
 * 一个待分析单元的抽取结果。
 *
 * <p><b>为什么失败也用同一个 record 返回而不是抛出去</b>（验收 ⑤：单 operation 失败不影响其他）：
 * 批量抽取时编排层要把 N 个单元的结果**汇总成一份报告**——哪些成功、各自抽到几条、
 * 哪几个失败、为什么失败。抛异常只能表达"第一个失败"，后面 N-1 个的去向就没了。
 * 因此 {@code TestPointExtractService.extract()} 把预期内的失败（上游抖动、输出修不好、
 * schema 不合规）都收进 {@code failed(...)}；真正的程序错误（NPE 之类）照旧抛出，不吞。</p>
 *
 * <p><b>{@code repaired} / {@code truncated} 逐单元携带</b>：这是 T04 {@code PromptPreviewVO} 的同款信号，
 * 但粒度更细——它们能回答"是哪个接口的抽取需要靠修复器救"。只看整篇的汇总时，
 * "修复率 30%"没有行动指引；落到单元上才知道该改提示词的哪一段。</p>
 *
 * @param sourceRef    来源定位（与 {@link ExtractUnit#sourceRef()} 一致，用于汇总时标识单元）
 * @param success      是否抽取成功并落库
 * @param parsedCount  模型返回的条数（去重前）
 * @param insertedCount 实际新增的条数（去重后；重复抽取同一单元时可能为 0）
 * @param repaired     模型输出是否被修复过
 * @param truncated    是否发生截断补全
 * @param error        失败原因（面向用户的中文，成功时为 {@code null}）
 * @author 寇豆码
 */
public record TestPointExtractResult(String sourceRef,
                                     boolean success,
                                     int parsedCount,
                                     int insertedCount,
                                     boolean repaired,
                                     boolean truncated,
                                     String error) {

    /**
     * 构造失败结果。
     *
     * @param sourceRef 来源定位
     * @param error     面向用户的中文失败原因
     * @return 失败结果（计数为 0，修复信号为 false）
     */
    public static TestPointExtractResult failed(String sourceRef, String error) {
        return new TestPointExtractResult(sourceRef, false, 0, 0, false, false, error);
    }
}
