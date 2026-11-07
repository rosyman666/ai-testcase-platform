package com.aittest.auth;

/**
 * 当前请求的登录上下文（ThreadLocal）。
 *
 * <p>为什么用 ThreadLocal 而不是把用户塞进 Controller 参数：47 个接口里 40 多个都需要
 * `created_by` / `reviewer_id`，逐个加参数会把签名污染得很难看。代价是**必须在
 * {@code afterCompletion} 里清理**（线程池复用，不清会串号），这一点由 AuthInterceptor 保证。</p>
 *
 * @author 寇豆码
 */
public final class AuthContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /**
     * 写入当前登录用户（由 AuthInterceptor 调用）。
     *
     * @param user 登录用户
     */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /**
     * 读取当前登录用户，未登录返回 null（不抛异常，供可选鉴权场景使用）。
     *
     * @return 登录用户或 null
     */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /**
     * 读取当前登录用户，未登录直接抛 40101。
     *
     * @return 登录用户（非 null）
     */
    public static LoginUser require() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw com.aittest.common.BizException.unauthorized();
        }
        return user;
    }

    /**
     * 取当前用户 ID，未登录抛 40101。
     *
     * @return 用户 ID
     */
    public static Long requireUserId() {
        return require().id();
    }

    /**
     * 清理（必须在请求结束时调用）。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
