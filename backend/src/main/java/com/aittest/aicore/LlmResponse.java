package com.aittest.aicore;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * 一次模型调用的返回（**未做结构化解析**，只有原始文本）。
 *
 * <p>解析成对象是 {@code aicore/structured/StructuredOutputParser} 的职责，Provider 只管
 * "把一句话要回来"。分开的理由：解析规则（去 fence、补尾逗号、校验 schema）与传输方式
 * （HTTP / mock）的变化频率完全不同，混在一起会让 mock 也得实现一遍修复逻辑。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString(exclude = {"rawBody"})
@Builder
public class LlmResponse {

    /** 模型输出的原始文本（可能带 markdown 围栏，交给解析器处理）。 */
    private final String content;

    /** token 用量。 */
    @Builder.Default
    private final LlmUsage usage = LlmUsage.EMPTY;

    /** 结束原因：{@code stop} / {@code length} / {@code content_filter}。 */
    @Builder.Default
    private final String finishReason = "stop";

    /** 本次调用耗时（毫秒），由 Provider 测量（含网络往返）。 */
    @Builder.Default
    private final long latencyMs = 0L;

    /** 上游原始响应体（排障用，**不进台账、不返回前端**：可能含完整提示词回显）。 */
    private final String rawBody;

    /** 实际使用的模型名（台账里记它，便于按模型归集成本）。 */
    private final String model;

    /**
     * 供应方自报的"降级"标记。
     *
     * <p>目前只有 mock 在用：当提示词里既没有 {@code testpoints} 也没有 {@code cases} 的结构线索、
     * 只能回一个兜底结构时置 {@code true}。它与 {@link LlmRequest#isDegraded()} 取"或"后落
     * {@code llm_call_log.degraded}，避免"调用方认为降级了、Provider 认为没有"时两边都记成 0。</p>
     */
    @Builder.Default
    private final boolean degraded = false;

    /**
     * 输出是否被上游截断（{@code finish_reason=length}）。
     *
     * <p>截断不算失败——它通常意味着 JSON 尾巴缺了一块，应该走"补全最后一个完整元素"的修复路径
     * （T04 ③ 第 4 类坏输出），而不是直接重试。</p>
     *
     * @return 是否被截断
     */
    public boolean isTruncated() {
        return "length".equalsIgnoreCase(finishReason);
    }
}
