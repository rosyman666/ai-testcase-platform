package com.aittest.auth.dto;

import java.time.Instant;

/**
 * 登录出参（{@code POST /api/auth/login}）。
 *
 * <p>{@code expiresAt} 用 {@link Instant} 而不是预先格式化好的字符串：交给
 * {@code JacksonConfig} 统一输出「固定 3 位毫秒 + Z」，避免各接口自行 {@code toString()}
 * 导致同一后端出现多种时间格式。前端按 ISO-8601 字符串解析即可。</p>
 *
 * @param token       访问令牌（放 {@code Authorization: Bearer <token>}）
 * @param expiresAt   过期时间（ISO-8601 UTC，JSON 中为字符串）
 * @param displayName 显示名
 * @param userId      用户 ID（Long，JSON 中为字符串）
 * @param username    登录名
 * @author 寇豆码
 */
public record LoginResponse(String token, Instant expiresAt, String displayName, Long userId, String username) {
}
