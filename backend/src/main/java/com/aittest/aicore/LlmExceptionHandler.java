package com.aittest.aicore;

import com.aittest.common.ApiResponse;
import com.aittest.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 模型调用异常的翻译层：把 {@link LlmException} 自带的错误码原样映射成响应体。
 *
 * <p><b>为什么必须单独有这么一层</b>：{@code LlmException} 继承 {@code RuntimeException} 而不是
 * {@code BizException}，因此 {@code GlobalExceptionHandler} 只认识它的父类 {@code Exception}，
 * 会把它统一落成 {@code 50000 系统内部错误}。这个失败方式极其隐蔽——</p>
 * <ul>
 *   <li>HTTP 仍是 {@code 200}，前端不会报网络错；</li>
 *   <li>台账里 {@code error_code} 记的是 {@code E_LLM_UPSTREAM}（装饰器直接读异常对象），
 *       于是"账本上是 50001、用户看到的是 50000"，两边对不上；</li>
 *   <li>用户在界面上看到"服务器开小差了"，而真实原因是"没配 Key"——他永远想不到去查配置。</li>
 * </ul>
 *
 * <p><b>为什么放在 {@code aicore} 而不是 {@code common}</b>：{@code common} 是跨域共用的底座
 * （{@code ApiResponse} / {@code ErrorCode} / {@code BizException}），{@code aicore} 是依赖它的业务模块。
 * 若把本类塞进 {@code common}，就形成 {@code common → aicore → common} 的包级循环依赖，
 * 以后想把 {@code common} 抽成独立 starter 会被这一个 import 卡住。让"谁定义异常、谁负责翻译"
 * 是同一条边界，比"所有 handler 挤在一个文件里"更可持续。</p>
 *
 * <p><b>为什么不用 {@code BizException} 包一层再抛</b>：那样每个调用点都要写一遍
 * {@code catch → new BizException(...)}。模型调用点已经有抽点/生成/修复/归因四个，
 * 加上"成功/失败"就是 8 条分支——与 {@code LoggingLlmProvider} 要解决的问题完全同构：
 * <b>逐个手写必有一条漏，而漏掉的那条只会静默降级为 50000</b>。集中翻译只写一次，漏不掉。</p>
 *
 * @author 寇豆码
 */
@RestControllerAdvice
public class LlmExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmExceptionHandler.class);

    /**
     * 把模型调用异常翻译成统一响应。
     *
     * @param ex      模型调用异常
     * @param request 当前请求（日志定位用）
     * @return {@code {code, message, data:{retryable}, traceId}}
     */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiResponse<Object>> handleLlmException(LlmException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();

        // retryable 必须回给调用方，不能只留在服务端：
        // 前端据此决定要不要显示"重试"按钮（429/超时该显示，401/模板缺失显示出来就是骗用户点），
        // 编排层据此决定要不要退避重试。丢了它，两边都只能"按错误码猜"（§8.7）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("retryable", ex.isRetryable());

        if (ex.isRetryable()) {
            // 可重试 = 上游抖动，属于预期内的失败，用 warn 且不带堆栈：
            // 限流风暴时每个请求一条 error 堆栈会把真正的配置问题淹掉
            log.warn("[LLM] 调用失败(可重试) uri={} code={} retryable=true msg={}",
                    request.getRequestURI(), errorCode.getCode(), ex.getMessage());
        } else {
            // 不可重试 = 配置/鉴权/模板缺失，重试一万次也不会好，必须有人来看，所以带堆栈进 error
            log.error("[LLM] 调用失败(不可重试) uri={} code={} retryable=false msg={}",
                    request.getRequestURI(), errorCode.getCode(), ex.getMessage(), ex);
        }

        // 文案优先用异常里的具体说明（"模型服务异常（HTTP 401）"比"模型服务异常，请稍后重试"有用得多），
        // 为空时才退到错误码默认文案。约定见 LlmException 构造函数：
        // message 是**面向用户的中文**，上游原始 HTML/堆栈只能进 cause，不能进 message
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? errorCode.getMessage()
                : ex.getMessage();

        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, message, payload));
    }
}
