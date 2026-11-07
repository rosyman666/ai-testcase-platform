package com.aittest.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常出口：**所有**异常都收敛成 {@code {code, message, data, traceId}}。
 *
 * <p>三条纪律：</p>
 * <ol>
 *   <li>只有 {@link BizException} 的 message 可以直接给用户看；其余异常一律返回固定中文文案，
 *       堆栈只进日志，靠 traceId 关联；</li>
 *   <li>HTTP 状态码由 {@link ErrorCode#getHttpStatus()} 决定（业务错误 200、鉴权 401、找不到 404）；</li>
 *   <li>不吞异常：未知异常必须 {@code log.error} 带堆栈，否则线上问题无从查起。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：按错误码映射 HTTP 状态与业务码。
     *
     * @param ex      业务异常
     * @param request 当前请求（用于日志定位）
     * @return 统一响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBizException(BizException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("业务异常(5xx) uri={} code={} msg={}", request.getRequestURI(), errorCode.getCode(), ex.getMessage(), ex);
        } else {
            log.warn("业务异常 uri={} code={} msg={}", request.getRequestURI(), errorCode.getCode(), ex.getMessage());
        }
        return build(errorCode, ex.getMessage(), ex.getPayload());
    }

    /**
     * 请求体字段校验失败（{@code @Valid @RequestBody}）。
     *
     * @param ex 校验异常
     * @return 40001 + 字段级错误明细
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        String message = fieldErrors.isEmpty()
                ? ErrorCode.E_PARAM_INVALID.getMessage()
                : fieldErrors.values().iterator().next();
        log.warn("参数校验失败 fields={}", fieldErrors);
        return build(ErrorCode.E_PARAM_INVALID, message, fieldErrors);
    }

    /**
     * 表单/查询参数绑定校验失败。
     *
     * @param ex 绑定异常
     * @return 40001
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Object>> handleBindException(BindException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return build(ErrorCode.E_PARAM_INVALID, ErrorCode.E_PARAM_INVALID.getMessage(), fieldErrors);
    }

    /**
     * 方法参数约束校验失败（{@code @Validated} + {@code @RequestParam}）。
     *
     * @param ex 约束异常
     * @return 40001
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return build(ErrorCode.E_PARAM_INVALID, message.isBlank() ? ErrorCode.E_PARAM_INVALID.getMessage() : message, null);
    }

    /**
     * 必填请求参数缺失。
     *
     * @param ex 缺参异常
     * @return 40002
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return build(ErrorCode.E_REQUIRED_MISSING, "缺少必填参数：" + ex.getParameterName(), null);
    }

    /**
     * 请求体不可解析（JSON 语法错误）或参数类型不匹配。
     *
     * @param ex 解析异常
     * @return 40001
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(Exception ex) {
        log.warn("请求体/参数格式错误：{}", ex.getMessage());
        return build(ErrorCode.E_PARAM_INVALID, "请求参数格式不正确", null);
    }

    /**
     * 路由不存在（依赖 {@code spring.mvc.throw-exception-if-no-handler-found=true}）。
     *
     * @param ex 无 handler 异常
     * @return 40401 + HTTP 404
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoHandlerFound(NoHandlerFoundException ex) {
        return build(ErrorCode.E_NOT_FOUND, "接口不存在：" + ex.getRequestURL(), null);
    }

    /**
     * 静态资源不存在（Spring 6.1+ 对未匹配路径抛 {@link NoResourceFoundException}）。
     *
     * <p>不单独处理它，请求 `<code>/api/xxx</code>（打错的接口路径）会被兜底处理器接住，
     * 返回 50000「系统内部错误」——把"接口写错了"报成"服务器崩了"，排障方向直接跑偏。</p>
     *
     * @param ex 资源不存在异常
     * @return 40401 + HTTP 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResourceFound(NoResourceFoundException ex) {
        return build(ErrorCode.E_NOT_FOUND, "接口不存在：" + ex.getResourcePath(), null);
    }

    /**
     * HTTP 方法不支持。
     *
     * @param ex 方法不支持异常
     * @return 40001
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.E_PARAM_INVALID, "不支持的请求方法：" + ex.getMethod(), null);
    }

    /**
     * 上传文件超限。
     *
     * @param ex 超限异常
     * @return 42002
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超出限制：{}", ex.getMessage());
        return build(ErrorCode.E_DOC_UNSUPPORTED, "文件超出大小限制（最大 20MB）", null);
    }

    /**
     * 兜底：未预期异常。
     *
     * @param ex      异常
     * @param request 当前请求
     * @return 50000，HTTP 200（按 §8.1 策略）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        // 必须带堆栈：这是排障的唯一线索，traceId 会一起进响应体
        log.error("未预期异常 uri={}", request.getRequestURI(), ex);
        return build(ErrorCode.E_INTERNAL, ErrorCode.E_INTERNAL.getMessage(), null);
    }

    /**
     * 统一的响应构造。
     *
     * @param errorCode 错误码
     * @param message   面向用户的文案
     * @param payload   附加数据
     * @return HTTP 状态 + 统一响应体
     */
    private ResponseEntity<ApiResponse<Object>> build(ErrorCode errorCode, String message, Object payload) {
        ApiResponse<Object> body = payload == null
                ? ApiResponse.fail(errorCode, message)
                : ApiResponse.fail(errorCode, message, payload);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
