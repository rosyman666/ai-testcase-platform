package com.aittest.aicore;

/**
 * 模型供应商抽象：<b>唯一</b>允许发起模型调用的入口。
 *
 * <p>实现只有三个（都在 {@code aicore/provider}）：</p>
 * <ul>
 *   <li>{@code MockLlmProvider} —— 离线可跑，无需任何 Key，输出确定；</li>
 *   <li>{@code OpenAiCompatibleProvider} —— 走 {@code /v1/chat/completions}，DeepSeek 与各类兼容网关通用；</li>
 *   <li>{@code LoggingLlmProvider} —— <b>装饰器</b>，本身不调模型，只负责记账后转发。</li>
 * </ul>
 *
 * <p><b>为什么业务层只能依赖这个接口、而不能直接 new OkHttp</b>：一旦出现第二条调用路径，
 * 记账、超时、温度、Key 的取法就会各写一遍。实测代价很具体——{@code llm_call_log} 少了行，
 * 成本看板就会"少算但不报错"，而少算的那部分恰好是失败重试（最贵的那部分）。</p>
 *
 * @author 寇豆码
 */
public interface LlmProvider {

    /**
     * 供应商标识，与 {@code ai.provider} 的取值一致。
     *
     * @return {@code mock} / {@code openai-compatible}
     */
    String name();

    /**
     * 是否支持某个模型名。
     *
     * <p>用于 {@code LlmProviderRouter} 在"显式指定模型"时的兜底判断：
     * mock 只认空模型名与 {@code mock-*}，其余实现一律返回 {@code true}
     * （兼容网关的模型名是运营侧随时可加的，代码里不能有白名单）。</p>
     *
     * @param model 模型名（可为空）
     * @return 是否支持
     */
    boolean supports(String model);

    /**
     * 发起一次对话调用。
     *
     * <p>实现约定：<b>失败必须抛 {@link LlmException}</b>（带错误码与可重试标记），
     * 不要返回 {@code null}、也不要返回空 content 冒充成功——空内容在编排层会被当成
     * "解析失败"，错误码会从"上游 500"漂移成"模型输出不合法"，排障方向直接跑偏。</p>
     *
     * @param request 调用入参
     * @return 原始文本结果
     */
    LlmResponse chat(LlmRequest request);
}
