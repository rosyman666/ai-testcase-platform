package com.aittest.common;

import com.aittest.logging.TraceFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * <p><b>三条不可破坏的契约</b>（前端拦截器与排障依赖，改一处就会静默出错）：</p>
 * <ol>
 *   <li>{@code data} 字段必须**始终出现**（{@code @JsonInclude(ALWAYS)}），禁止改 NON_NULL——
 *       前端靠"{@code data} 键是否存在"判断 schema，缺了会拿到 undefined 而不是 null；</li>
 *   <li>{@code traceId} 取自 MDC，键名固定为 {@link TraceFilter#MDC_TRACE_ID}，
 *       改名不会报错，只会让所有响应的 traceId 静默变成空串；</li>
 *   <li>{@code code} 取值必须与 §8.1 错误码表一致（{@link ErrorCode} 是唯一真源，50000 仍是 50000）。</li>
 * </ol>
 *
 * @param <T> 业务数据类型
 * @author 寇豆码
 */
@Getter
@ToString
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> {

    /** 成功码。 */
    public static final int CODE_OK = ErrorCode.OK.getCode();

    /** 成功提示文案。 */
    public static final String MESSAGE_OK = ErrorCode.OK.getMessage();

    /** 未预期异常的码值（保留常量名以兼容 T01 既有用法，取值从 ErrorCode 派生，数值仍为 50000）。 */
    public static final int CODE_INTERNAL_ERROR = ErrorCode.E_INTERNAL.getCode();

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
     * 构造失败响应（裸码值）。
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
     * 构造失败响应（错误码枚举 + 默认文案）。
     *
     * @param errorCode 错误码枚举
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    /**
     * 构造失败响应（错误码枚举 + 自定义文案）。
     *
     * @param errorCode 错误码枚举
     * @param message   面向用户的中文提示
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, currentTraceId());
    }

    /**
     * 构造失败响应（错误码枚举 + 自定义文案 + 附加数据）。
     *
     * <p>附加数据用于字段级校验错误等场景：{@code code} 仍表示失败，{@code data} 携带明细
     * 供前端做内联提示；不要用它表达"部分成功"。</p>
     *
     * @param errorCode 错误码枚举
     * @param message   面向用户的中文提示
     * @param payload   附加数据，可为 null
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T payload) {
        return new ApiResponse<>(errorCode.getCode(), message, payload, currentTraceId());
    }

    /**
     * 是否为成功响应。
     *
     * <p><b>必须加 {@code @JsonIgnore}</b>：Jackson 会把它当成 {@code success} 属性序列化出去，
     * 从而破坏 §8.1 规定的四字段结构（多一个字段就可能让前端"严格校验 schema"的逻辑失效）。</p>
     *
     * @return {@code code == 0} 返回 true
     */
    @JsonIgnore
    public boolean isSuccess() {
        return this.code == CODE_OK;
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
