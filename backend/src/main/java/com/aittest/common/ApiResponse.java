package com.aittest.common;

import com.aittest.logging.TraceFilter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.MDC;

/**
 * 统一响应体：{@code {code, message, data, traceId}}，{@code code=0} 表示成功。
 *
 * <p>约定（见架构文档 §8.1）：业务错误统一返回 HTTP 200 + {@code code!=0}，
 * 前端 axios 拦截器只按 {@code code} 判断；{@code message} 是可直接 toast 的中文文案，
 * 技术细节只进日志。</p>
 *
 * @param <T> 业务数据类型
 * @author 寇豆码
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    /** 成功码。 */
    public static final int CODE_OK = 0;

    /** 成功提示文案。 */
    public static final String MESSAGE_OK = "ok";

    /** 未预期异常（T02 的错误码枚举会补齐全部码值）。 */
    public static final int CODE_INTERNAL_ERROR = 50000;

    private final int code;

    private final String message;

    private final T data;

    private final String traceId;

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    /**
     * 构造成功响应。
     *
     * @param data 业务数据，可为 null
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, MESSAGE_OK, data, currentTraceId());
    }

    /**
     * 构造成功响应（无数据体）。
     *
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 构造失败响应。
     *
     * @param code    错误码（非 0）
     * @param message 面向用户的中文提示
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, currentTraceId());
    }

    /**
     * 取当前请求的 traceId；非 Web 线程（如定时任务）下退化为空串，保证字段存在。
     *
     * @return traceId，永不为 null
     */
    private static String currentTraceId() {
        String traceId = MDC.get(TraceFilter.MDC_TRACE_ID);
        return traceId == null ? "" : traceId;
    }
}
