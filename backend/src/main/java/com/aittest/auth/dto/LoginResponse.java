package com.aittest.auth.dto;

/**
 * 登录出参（{@code POST /api/auth/login}）。
 *
 * <p>{@code expiresAt} 用 ISO-8601 UTC 字符串而不是 epoch 数字：全局约定时间一律 ISO 字符串，
 * 前端直接用 dayjs 解析，不必再判断"这个数字是秒还是毫秒"。</p>
 *
 * @param token       访问令牌（放 {@code Authorization: Bearer <token>}）
 * @param expiresAt   过期时间（ISO-8601 UTC）
 * @param displayName 显示名
 * @param userId      用户 ID（Long，JSON 中为字符串）
 * @param username    登录名
 * @author 寇豆码
 */
public record LoginResponse(String token, String expiresAt, String displayName, Long userId, String username) {
}
