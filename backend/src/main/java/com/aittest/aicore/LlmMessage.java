package com.aittest.aicore;

/**
 * 一条对话消息：{@code role} + {@code content}。
 *
 * <p>{@code role} 只有三个合法值，与 OpenAI 的 {@code messages[].role} 一一对应。
 * 用常量而不是枚举：这个类要 <b>原样</b>序列化成上游的 JSON，枚举会让序列化多一层
 * （{@code "role":"SYSTEM"} 还是 {@code "system"} 取决于 Jackson 配置），而上游只认小写。</p>
 *
 * @param role    角色：{@code system} / {@code user} / {@code assistant}
 * @param content 消息正文
 * @author 寇豆码
 */
public record LlmMessage(String role, String content) {

    /** 系统提示词角色。 */
    public static final String ROLE_SYSTEM = "system";

    /** 用户角色。 */
    public static final String ROLE_USER = "user";

    /** 模型角色（多轮时把历史回答塞回去）。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 构造用户消息。
     *
     * @param content 正文
     * @return 用户消息
     */
    public static LlmMessage user(String content) {
        return new LlmMessage(ROLE_USER, content);
    }

    /**
     * 构造系统消息。
     *
     * @param content 正文
     * @return 系统消息
     */
    public static LlmMessage system(String content) {
        return new LlmMessage(ROLE_SYSTEM, content);
    }

    /**
     * 构造模型消息（修复式重试时要把上一次的坏输出贴回去）。
     *
     * @param content 正文
     * @return 模型消息
     */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(ROLE_ASSISTANT, content);
    }
}
