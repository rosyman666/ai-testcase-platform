package com.aittest.auth;

/**
 * 当前登录用户（存于 {@link AuthContext} 的线程变量）。
 *
 * @param id          用户主键（雪花 ID，序列化时由 JacksonConfig 转字符串）
 * @param username    登录名
 * @param displayName 显示名
 * @author 寇豆码
 */
public record LoginUser(Long id, String username, String displayName) {
}
