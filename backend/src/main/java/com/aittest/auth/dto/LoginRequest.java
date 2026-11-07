package com.aittest.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录入参（{@code POST /api/auth/login}）。
 *
 * @param username 登录名
 * @param password 明文密码（只在传输中出现，服务端立即 BCrypt 比对，绝不落库/落日志）
 * @author 寇豆码
 */
public record LoginRequest(

        @NotBlank(message = "请输入用户名")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        String username,

        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password) {
}
