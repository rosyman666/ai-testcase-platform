package com.aittest.auth;

import com.aittest.auth.domain.AppUser;
import com.aittest.auth.dto.LoginRequest;
import com.aittest.auth.dto.LoginResponse;
import com.aittest.auth.dto.UserVO;
import com.aittest.auth.mapper.AppUserMapper;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 鉴权服务：登录校验、当前用户解析。
 *
 * <p>安全细节：</p>
 * <ol>
 *   <li>用户名不存在与密码错误返回<b>同一句</b>文案（"用户名或密码错误"），避免账号枚举；</li>
 *   <li>用 BCrypt 比对（自带盐、慢哈希），绝不比较明文；</li>
 *   <li>失败只在日志里记用户名（不记密码、不记哈希），便于排查撞库。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 统一的登录失败文案（不区分"用户不存在"与"密码错"，防账号枚举）。 */
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private final AppUserMapper appUserMapper;

    private final TokenService tokenService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AppUserMapper appUserMapper, TokenService tokenService) {
        this.appUserMapper = appUserMapper;
        this.tokenService = tokenService;
    }

    /**
     * 登录。
     *
     * @param request 登录入参
     * @return token 与用户信息
     */
    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        AppUser user = appUserMapper.selectOne(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username).last("LIMIT 1"));

        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败：凭证不匹配 username={}", username);
            throw new BizException(ErrorCode.E_UNAUTHORIZED, LOGIN_FAILED_MESSAGE);
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            log.warn("登录失败：账号已禁用 username={}", username);
            throw new BizException(ErrorCode.E_FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        TokenService.IssuedToken issued = tokenService.issue(
                new LoginUser(user.getId(), user.getUsername(), user.getDisplayName()));
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());
        return new LoginResponse(
                issued.token(),
                Instant.ofEpochSecond(issued.expiresAt()).toString(),
                user.getDisplayName(),
                user.getId(),
                user.getUsername());
    }

    /**
     * 当前登录用户信息（token 有效性探针）。
     *
     * @return 用户信息
     */
    public UserVO currentUser() {
        LoginUser current = AuthContext.require();
        AppUser user = appUserMapper.selectById(current.id());
        if (user == null) {
            // token 合法但用户已被删除：视为未登录，让前端清 token 重新登录
            throw new BizException(ErrorCode.E_UNAUTHORIZED, "账号不存在，请重新登录");
        }
        return UserVO.of(user);
    }
}
