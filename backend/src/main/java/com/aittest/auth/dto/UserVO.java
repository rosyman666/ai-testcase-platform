package com.aittest.auth.dto;

import com.aittest.auth.domain.AppUser;
import java.time.LocalDateTime;

/**
 * 当前用户信息（{@code GET /api/auth/me}）。
 *
 * <p>前端用它验证"token 是否还有效"以及渲染右上角用户名；{@code id} 是 Long
 * （JSON 中为字符串，前端 TS 类型必须声明为 {@code string}）。</p>
 *
 * @param id           用户 ID
 * @param username     登录名
 * @param displayName  显示名
 * @param enabled      是否启用
 * @param createdAt    创建时间
 * @author 寇豆码
 */
public record UserVO(Long id, String username, String displayName, Boolean enabled, LocalDateTime createdAt) {

    /**
     * 由实体转换。
     *
     * @param user 用户实体
     * @return VO
     */
    public static UserVO of(AppUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getDisplayName(),
                Boolean.TRUE.equals(user.getEnabled()), user.getCreatedAt());
    }
}
