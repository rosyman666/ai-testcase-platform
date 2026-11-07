package com.aittest.aicore;

import com.aittest.common.ErrorCode;
import lombok.Getter;

/**
 * 模型调用异常：既携带<b>对外的错误码</b>，也携带<b>对内的可重试判定</b>。
 *
 * <p><b>为什么不直接抛 {@code BizException}</b>：{@code BizException} 只有错误码，
 * 而编排层要做的是"这次失败要不要再试一次"——{@code 429}/{@code 5xx}/超时该退避重试，
 * {@code 401 鉴权失败}/模板缺失重试一万次也不会好。把 {@link #isRetryable()} 放在异常里，
 * 判据就跟着失败原因一起走，调用方不需要再"按错误码猜"。猜错的代价很实在：
 * 重试 {@code 401} 会把配额白烧 3 倍，且日志里全是同一条 401，看起来像"上游一直挂"。</p>
 *
 * <p>它<b>继承 RuntimeException</b>：模型调用是 I/O，不希望在 service 层到处 {@code throws}。
 * 翻译成 HTTP 响应由 {@link LlmExceptionHandler} 集中完成，<b>业务层不要再自己 try/catch 转
 * {@code BizException}</b>：那样每个调用点都要写一遍，而漏掉的那一处只会静默降级成 {@code 50000}——
 * 用户看到"服务器开小差了"，台账里却记着 {@code E_LLM_UPSTREAM}，两边对不上。</p>
 *
 * <p>构造时的 {@code message} 会<b>原样返回给用户</b>：只能写面向用户的中文，
 * 上游原始 HTML/堆栈必须放进 {@code cause}。这一条是安全边界，不是文风要求。</p>
 *
 * @author 寇豆码
 */
@Getter
public class LlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 对外错误码（{@code E_LLM_*} 系列）。 */
    private final ErrorCode errorCode;

    /** 是否值得重试（退避重试由编排层 {@code RetryPolicy} 决定，这里只给判定）。 */
    private final boolean retryable;

    /**
     * 构造异常。
     *
     * @param errorCode 对外错误码
     * @param message   面向用户的中文说明（**不要塞上游原始 HTML/堆栈**）
     * @param retryable 是否值得重试
     */
    public LlmException(ErrorCode errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 构造异常并保留根因。
     *
     * @param errorCode 对外错误码
     * @param message   面向用户的中文说明
     * @param retryable 是否值得重试
     * @param cause     根因（只进日志）
     */
    public LlmException(ErrorCode errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 配置缺失（如未配 API Key）：不可重试，且文案要直接说清楚该配哪个键。
     *
     * @param message 中文说明
     * @return 不可重试的上游异常
     */
    public static LlmException misconfigured(String message) {
        return new LlmException(ErrorCode.E_LLM_UPSTREAM, message, false);
    }
}
