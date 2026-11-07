package com.aittest.aicore.prompt.dto;

/**
 * 模板试跑出参。
 *
 * <p><b>为什么把 {@code repaired} / {@code truncated} 也返回</b>：这两个字段是提示词质量的
 * 早期信号。模型输出被修复才解析成功，说明模板的输出约束写得不够硬；
 * 被截断说明 {@code max_tokens} 或"一次要太多条"出了问题。只看"试跑成功"会漏掉这两类退化——
 * 它们都能让链路绿着跑完，只有采纳率在下滑。</p>
 *
 * @param provider         实际使用的供应商
 * @param model            实际使用的模型
 * @param content          模型原始输出（截断到 8000 字符，够看形状又不至于把响应撑爆）
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param latencyMs        耗时（毫秒）。<b>用 Integer 而不是 Long</b>：全局 Jackson 把 Long 序列化成字符串
 *                         （防前端精度丢失），耗时变成 "153" 会让前端排序按字典序比较
 * @param repaired         输出是否被修复过（为真说明模型没按"只输出 JSON"来）
 * @param truncated        是否发生截断补全（为真应把 {@code degraded} 视为 1）
 * @param schemaValid      是否通过 schema 校验（模板没配 schema 时为 {@code null}）
 * @param schemaError      schema 校验失败原因（含字段路径）
 * @param parsedCount      解析出的元素个数（未解析时为 {@code null}）
 * @author 寇豆码
 */
public record PromptPreviewVO(String provider, String model, String content,
                              Integer promptTokens, Integer completionTokens, Integer latencyMs,
                              Boolean repaired, Boolean truncated,
                              Boolean schemaValid, String schemaError, Integer parsedCount) {
}
