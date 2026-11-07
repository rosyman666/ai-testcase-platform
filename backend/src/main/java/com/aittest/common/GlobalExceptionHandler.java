package com.aittest.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** MySQL：{@code Data too long for column 'x' at row n}。 */
    private static final int MYSQL_DATA_TOO_LONG = 1406;

    /** MySQL：{@code Duplicate entry 'x' for key 'y'}。 */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /** 从 1406 消息里抠列名（只取标识符字符，避免把引号/转义带出去）。 */
    private static final Pattern DATA_TOO_LONG = Pattern.compile("Data too long for column '([A-Za-z0-9_]+)'");

    /**
     * 从 1062 消息里抠索引名。
     *
     * <p>MySQL 8.0.19 起是 {@code for key 'doc_source.uk_doc_source_doc_no'}（带表名），
     * 更早是 {@code for key 'uk_doc_source_doc_no'}，两种都要能认。<b>绝不捕获 {@code Duplicate entry 'xxx'} 里的键值</b>——
     * 那是最该保密的部分（见 §8.1）。</p>
     */
    private static final Pattern DUPLICATE_KEY = Pattern.compile("for key '(?:[A-Za-z0-9_]+\\.)?([A-Za-z0-9_]+)'");

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
     * 数据库约束被撞：**把 {@code 1406}/{@code 1062} 从 {@code 50000} 里拆出来**（§8.1 / T04 ⑨）。
     *
     * <p><b>为什么必须单独接住</b>：这两类都是"应用层校验漏网"的症状，而不是"系统坏了"。
     * 落到兜底 {@code Exception → 50000} 有两个后果：① 错误码说谎（用户以为服务异常，实际是他输入超长）；
     * ② <b>{@code 50000} 失去信噪比</b>——它本该只在"我们确实没想到"时出现，一旦开始覆盖
     * "我们想到了但没接"，这个码就废了，监控告警的阈值也就没法定了。</p>
     *
     * <p><b>三条不能破的纪律</b>：① 字段名必须走 {@link ColumnWhitelist} 白名单后再回显，
     * <b>禁止把 SQL 原文吐给前端</b>（会泄露表结构）；② 1062 只给冲突类型，<b>不返回键值本身</b>；
     * ③ 两者都带 {@code traceId}（由 {@link ApiResponse} 从 MDC 取，自动生效）。</p>
     *
     * <p><b>为什么兜底分支仍是 {@code 50000}</b>：外键 {@code 1452}、非空 {@code 1048} 这类既不是用户
     * 输入问题、也没有定义过业务码，强行归到 {@code 40001} 是另一种说谎。这里保留 {@code 50000}，
     * 但把 SQL 原文按 {@code log.error} 打进日志——**错误码给用户看，根因给运维看**。</p>
     *
     * @param ex      数据完整性异常
     * @param request 当前请求
     * @return 40004 / 40902 / 50000
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrity(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        SQLException sqlEx = findSqlException(ex);
        int vendorCode = sqlEx == null ? -1 : sqlEx.getErrorCode();
        String rawMessage = sqlEx == null ? String.valueOf(ex.getMostSpecificCause().getMessage()) : sqlEx.getMessage();

        if (vendorCode == MYSQL_DATA_TOO_LONG) {
            String column = matchGroup(DATA_TOO_LONG, rawMessage);
            ColumnWhitelist.Column known = ColumnWhitelist.find(column);
            Map<String, Object> payload = null;
            if (known != null) {
                payload = new LinkedHashMap<>();
                // 只回显**白名单里的**列名；未登记时 payload 为空，一个字母都不吐
                payload.put("field", column);
                if (known.maxLength() > 0) {
                    payload.put("maxLength", known.maxLength());
                }
            }
            // 原文只进日志：这是排障需要，但绝不能出现在响应体里
            log.warn("字段超长 uri={} column={} sql={}", request.getRequestURI(), column, rawMessage);
            return build(ErrorCode.E_FIELD_TOO_LONG, ColumnWhitelist.describe(column), payload);
        }

        if (vendorCode == MYSQL_DUPLICATE_ENTRY) {
            String keyName = matchGroup(DUPLICATE_KEY, rawMessage);
            String conflictOn = conflictOf(keyName);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conflictOn", conflictOn);
            log.warn("唯一键冲突 uri={} key={} conflictOn={}", request.getRequestURI(), keyName, conflictOn);
            return build(ErrorCode.E_DUPLICATE, duplicateMessage(conflictOn), payload);
        }

        // 其余约束（外键/非空/CHECK）：没有业务码，但必须带堆栈进日志，否则"保存失败"无从查
        log.error("数据完整性异常 uri={} sqlState={} sql={}", request.getRequestURI(),
                sqlEx == null ? "" : sqlEx.getSQLState(), rawMessage, ex);
        return build(ErrorCode.E_INTERNAL, "数据保存失败，请稍后重试", null);
    }

    /**
     * 沿异常链找第一个带厂商错误码的 {@link SQLException}。
     *
     * <p>不能只看 {@code ex.getMostSpecificCause()}：Spring 有可能把 SQLException 包在
     * {@code MyBatisSystemException} 里，而 {@code getErrorCode()} 只有 {@link SQLException} 才有。</p>
     *
     * @param ex 数据完整性异常
     * @return 找到的 SQLException，没有返回 {@code null}
     */
    private SQLException findSqlException(Throwable ex) {
        Throwable current = ex;
        // 深度上限防环（异常链理论上不会成环，但自定义 cause 可能自引用）
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof SQLException sqlEx) {
                return sqlEx;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    /**
     * 取第一个捕获组，未匹配返回空串。
     *
     * @param pattern 正则
     * @param text    被匹配文本
     * @return 捕获组内容（可能为空串）
     */
    private String matchGroup(Pattern pattern, String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 索引名 → 冲突类型（**只允许这三个取值**，§8.1）。
     *
     * @param keyName 索引名，如 {@code doc_source.uk_doc_source_doc_no}
     * @return {@code doc_no} / {@code file_hash} / {@code other}
     */
    private String conflictOf(String keyName) {
        if (keyName.contains("doc_no")) {
            return "doc_no";
        }
        if (keyName.contains("file_hash")) {
            return "file_hash";
        }
        return "other";
    }

    /**
     * 冲突类型 → 中文文案。
     *
     * @param conflictOn 冲突类型
     * @return 可直接 toast 的中文
     */
    private String duplicateMessage(String conflictOn) {
        return switch (conflictOn) {
            case "doc_no" -> "该文档单号已存在，请重试";
            case "file_hash" -> "相同内容的文件已存在，请勿重复上传";
            default -> "数据已存在，请勿重复提交";
        };
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
