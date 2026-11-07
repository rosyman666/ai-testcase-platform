package com.aittest.aicore;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.ToString;

/**
 * 一次模型调用的完整入参。
 *
 * <p><b>两类字段必须分开看</b>：</p>
 * <ol>
 *   <li><b>发给上游的</b>：{@link #systemPrompt} / {@link #messages} / {@link #temperature} /
 *       {@link #maxTokens} / {@link #jsonSchema} / {@link #timeoutMs}；</li>
 *   <li><b>只用于记账的</b>：{@link #traceId} / {@link #scene} / {@link #taskId} /
 *       {@link #taskItemId} / {@link #attemptNo} / {@link #degraded}。</li>
 * </ol>
 *
 * <p><b>为什么把"记账字段"塞进请求对象</b>：{@code llm_call_log} 要求
 * {@code trace_id/scene/task_id/task_item_id/attempt_no} 齐全（§6 T04 ⑦⑧），
 * 而记账发生在<b>装饰器</b>里（{@code LoggingLlmProvider}），它除了这个请求对象之外
 * 什么都看不到。如果改成"调用方事后自己补日志"，mock 与真实 provider、成功与异常
 * 四条路径就会各写一遍，其中必有一条漏字段——这正是 D-10 把记账收进装饰器的原因。</p>
 *
 * @author 寇豆码
 */
@Getter
@ToString(exclude = {"systemPrompt", "messages", "jsonSchema"})
@Builder
public class LlmRequest {

    /** 调用场景，取值同 {@code common.enums.TaskScene} 的 code（如 {@code TESTPOINT_EXTRACT}）。 */
    private final String scene;

    /** 链路 ID，与响应头 {@code X-Trace-Id} 一致，用于把台账与请求日志串起来。 */
    private final String traceId;

    /** 关联任务 ID（可空：单次试跑没有任务）。 */
    private final Long taskId;

    /** 关联任务子项 ID（可空）。 */
    private final Long taskItemId;

    /**
     * 第几次尝试，从 1 开始。
     *
     * <p><b>它决定台账是"追加"还是"覆盖"</b>：{@code llm_call_log} 永远是 INSERT，
     * 同一 item 的 3 次重试落 3 行、{@code attempt_no} 分别为 1/2/3——只有这样才能
     * 算出"首次通过率"与"平均重试次数"，覆盖式记账会把这两条指标直接抹平。</p>
     */
    @Builder.Default
    private final int attemptNo = 1;

    /**
     * 本次调用是否属于<b>降级调用</b>（由调用方声明）。
     *
     * <p>典型场景：修复轮次（{@code CASE_REPAIR}）本身就是降级路径；宽松解析轮次同理。
     * 与 {@link LlmResponse#isDegraded()} 是"或"的关系：一方为真即记 1（D-04）。</p>
     */
    @Builder.Default
    private final boolean degraded = false;

    /** 系统提示词（可为空；为空时只发 messages）。 */
    private final String systemPrompt;

    /** 对话消息列表（顺序有意义）。 */
    @Singular("message")
    private final List<LlmMessage> messages;

    /** 采样温度：抽点 0.2 / 生成 0.4 / 修复 0.0（§8.7 第 1 条）。 */
    @Builder.Default
    private final double temperature = 0.2D;

    /** 输出 token 上限；{@code 0} 表示不限制（由上游默认值决定）。 */
    @Builder.Default
    private final int maxTokens = 0;

    /**
     * 期望输出的 JSON Schema 原文。
     *
     * <p>两个用途：① {@code openai-compatible} 下作为 {@code response_format=json_object} 的依据；
     * ② mock 下按它<b>认出该吐哪种假数据</b>（见 {@code MockLlmProvider}）。
     * 为空表示"这次调用不要求结构化输出"。</p>
     */
    private final String jsonSchema;

    /** 单次调用超时（毫秒）；{@code 0} 表示用 {@code ai.timeout-seconds} 的配置值。 */
    @Builder.Default
    private final int timeoutMs = 0;

    /**
     * 拼出"全部提示文本"，供 mock 做确定性选样与日志截断。
     *
     * @return 系统提示词 + 所有消息正文，用换行连接
     */
    public String allText() {
        List<String> parts = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            parts.add(systemPrompt);
        }
        if (messages != null) {
            for (LlmMessage message : messages) {
                if (message != null && message.content() != null) {
                    parts.add(message.content());
                }
            }
        }
        return String.join("\n", parts);
    }
}
