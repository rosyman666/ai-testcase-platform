package com.aittest.auth;

import com.aittest.auth.dto.LoginRequest;
import com.aittest.auth.dto.LoginResponse;
import com.aittest.auth.dto.UserVO;
import com.aittest.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口。
 *
 * <p>{@code /api/auth/login} 在鉴权白名单内（见 {@code WebMvcConfig}），
 * {@code /api/auth/me} 需要 token —— 后者正是前端"token 是否还有效"的探针。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "鉴权", description = "登录与当前用户")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录（白名单，无需 token）。
     *
     * @param request 登录入参
     * @return token 与用户信息
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "返回无状态 token；失败统一提示「用户名或密码错误」")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 当前登录用户。
     *
     * @return 用户信息
     */
    @GetMapping("/me")
    @Operation(summary = "当前用户", description = "用于前端校验 token 是否仍有效")
    public ApiResponse<UserVO> me() {
        return ApiResponse.ok(authService.currentUser());
    }
}
