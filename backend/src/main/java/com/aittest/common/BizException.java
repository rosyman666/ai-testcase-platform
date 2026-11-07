package com.aittest.common;

import lombok.Getter;

/**
 * 业务异常：**唯一**允许在 service / domain 层主动抛出的异常类型。
 *
 * <p>约定：{@code message} 必须是可直接展示给用户的中文；技术细节（堆栈、上游原文）
 * 一律走 {@code log.warn/error}，不要塞进 message——否则会泄漏内部信息，
 * 也会让前端 toast 出现英文堆栈。</p>
 *
 * @author 寇豆码
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码。 */
    private final ErrorCode errorCode;

    /** 附加数据（可选，如校验失败的字段列表），随 data 返回。 */
    private final transient Object payload;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.payload = null;
    }

    /**
     * 使用自定义中文文案覆盖错误码的默认文案。
     *
     * @param errorCode 业务错误码
     * @param message   面向用户的中文提示
     */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.payload = null;
    }

    /**
     * 使用自定义文案并携带附加数据。
     *
     * @param errorCode 业务错误码
     * @param message   面向用户的中文提示
     * @param payload   附加数据（可为 null）
     */
    public BizException(ErrorCode errorCode, String message, Object payload) {
        super(message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    /**
     * 快捷构造：未登录。
     *
     * @return 40101 异常
     */
    public static BizException unauthorized() {
        return new BizException(ErrorCode.E_UNAUTHORIZED);
    }

    /**
     * 快捷构造：资源不存在。
     *
     * @param what 资源描述，如「文档」
     * @return 40401 异常
     */
    public static BizException notFound(String what) {
        return new BizException(ErrorCode.E_NOT_FOUND, what + "不存在或已被删除");
    }
}
