package com.aittest.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 全局错误码（见架构文档 §8.1，**码值与文档严格一致，前端按码值分支，禁止改动**）。
 *
 * <p>HTTP 状态码策略：业务错误统一返回 HTTP 200 + {@code code != 0}，仅
 * 鉴权失败（401）、未找到（404）、限流（429）使用真实 HTTP 状态码，以兼容网关与浏览器行为。
 * 因此 50000 虽然是"系统内部错误"，HTTP 仍是 200，前端只按 {@code code} 判断。</p>
 *
 * @author 寇豆码
 */
@Getter
public enum ErrorCode {

    /** 成功。 */
    OK(0, "ok", HttpStatus.OK),

    /** 参数校验失败（Bean Validation 不通过、JSON 结构错误）。 */
    E_PARAM_INVALID(40001, "参数校验失败", HttpStatus.OK),

    /** 必填缺失（如驳回未选原因）。 */
    E_REQUIRED_MISSING(40002, "必填参数缺失", HttpStatus.OK),

    /** 无可导出/可操作数据。 */
    E_EMPTY_RESULT(40003, "没有可处理的数据", HttpStatus.OK),

    /**
     * 字段内容超长（DB {@code 1406 Data too long} 的应用侧映射，见 §8.1 / §8.1.1）。
     *
     * <p>它存在的前提是 {@code sql_mode} 含 {@code STRICT_TRANS_TABLES}：没有 strict 模式时
     * MySQL 会**静默截断并返回成功**，这条错误永远不会被触发，而脏数据已经进库了。</p>
     */
    E_FIELD_TOO_LONG(40004, "字段内容超出长度限制", HttpStatus.OK),

    /** 未登录 / token 缺失。 */
    E_UNAUTHORIZED(40101, "登录已失效，请重新登录", HttpStatus.UNAUTHORIZED),

    /** token 过期或被篡改。 */
    E_TOKEN_INVALID(40102, "登录状态异常，请重新登录", HttpStatus.UNAUTHORIZED),

    /** 无权限操作该资源。 */
    E_FORBIDDEN(40301, "没有权限执行该操作", HttpStatus.FORBIDDEN),

    /** 资源不存在。 */
    E_NOT_FOUND(40401, "请求的资源不存在", HttpStatus.NOT_FOUND),

    /** 状态机拒绝（如对 SUCCESS 任务 cancel）。 */
    E_STATE_ILLEGAL(40901, "当前状态不允许该操作", HttpStatus.OK),

    /**
     * 唯一键冲突（DB {@code 1062 Duplicate entry} 的应用侧映射，见 §8.1）。
     *
     * <p>{@code data.conflictOn} 只给**冲突类型**（{@code doc_no} / {@code file_hash} / {@code other}），
     * 不返回键值本身——键值里往往含用户数据，且回显它等于把唯一索引结构告诉调用方。</p>
     */
    E_DUPLICATE(40902, "数据已存在，请勿重复提交", HttpStatus.OK),

    /** 文档解析失败。 */
    E_DOC_PARSE_FAILED(42001, "文档解析失败", HttpStatus.OK),

    /** 不支持的格式 / 超大小。 */
    E_DOC_UNSUPPORTED(42002, "不支持的文件格式或文件超出大小限制", HttpStatus.OK),

    /** 文档未解析完成就发起生成。 */
    E_DOC_NOT_PARSED(42003, "文档尚未解析完成", HttpStatus.OK),

    /** 任务非 PARTIAL/FAILED 不可重试。 */
    E_TASK_NOT_RETRYABLE(43001, "当前任务状态不支持重试", HttpStatus.OK),

    /** 任务超 deadline。 */
    E_TASK_DEADLINE(43002, "任务已超时，请重新发起", HttpStatus.OK),

    /** 上游 5xx / 网络错误（重试耗尽）。 */
    E_LLM_UPSTREAM(50001, "模型服务异常，请稍后重试", HttpStatus.OK),

    /** 调用超时。 */
    E_LLM_TIMEOUT(50002, "模型调用超时，建议稍后重试或切换模型", HttpStatus.OK),

    /** 429 限流。 */
    E_LLM_RATE_LIMIT(50003, "模型服务限流，请稍后重试", HttpStatus.OK),

    /** 结构化输出 3 轮修复仍失败。 */
    E_LLM_PARSE_EXHAUSTED(50004, "模型输出无法解析为有效结构", HttpStatus.OK),

    /** JSON Schema 校验不通过。 */
    E_SCHEMA_INVALID(50005, "模型输出未通过结构校验", HttpStatus.OK),

    /** 未预期异常。 */
    E_INTERNAL(50000, "系统内部错误，请稍后重试", HttpStatus.OK);

    /** 业务码（0 = 成功）。 */
    private final int code;

    /** 默认中文文案（可直接 toast）。 */
    private final String message;

    /** 对应 HTTP 状态码。 */
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * 按业务码反查枚举，未知码统一归 {@link #E_INTERNAL}。
     *
     * @param code 业务码
     * @return 对应枚举，未知返回 {@link #E_INTERNAL}
     */
    public static ErrorCode of(int code) {
        for (ErrorCode item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return E_INTERNAL;
    }
}
