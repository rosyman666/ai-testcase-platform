package com.aittest.aicore;

/**
 * token 用量：{@code prompt / completion / total}。
 *
 * <p>{@code totalTokens} 是<b>派生值</b>，不单独存字段：上游偶尔返回三方不一致的
 * {@code total_tokens}（多见于网关二次加工），以 prompt+completion 为准能保证
 * "成本 = 输入单价×prompt + 输出单价×completion"这条账永远自洽。</p>
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @author 寇豆码
 */
public record LlmUsage(int promptTokens, int completionTokens) {

    /** 全零用量（mock 未统计、或上游未返回 usage 时用）。 */
    public static final LlmUsage EMPTY = new LlmUsage(0, 0);

    /**
     * 总 token 数。
     *
     * @return prompt + completion
     */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
