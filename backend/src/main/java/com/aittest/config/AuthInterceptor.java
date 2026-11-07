package com.aittest.config;

import com.aittest.auth.AuthContext;
import com.aittest.auth.LoginUser;
import com.aittest.auth.TokenService;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器：校验 {@code Authorization: Bearer <token>}，通过后写入 {@link AuthContext}。
 *
 * <p>兼容 SSE：{@code EventSource} 无法自定义请求头，所以 {@code /api/tasks/{id}/stream}
 * 允许把 token 放在查询参数 {@code ?token=}（见 §4.3）。两种来源的优先级：
 * Header 优先（更安全，不会进访问日志），其次查询参数。</p>
 *
 * <p>白名单不在这里判断，由 {@link WebMvcConfig#AUTH_WHITELIST} 统一声明——
 * 拦截器内散落路径判断是"白名单漏配"事故的常见根源。</p>
 *
 * @author 寇豆码
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 标准鉴权头。 */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** SSE 场景的查询参数名。 */
    private static final String TOKEN_QUERY_PARAM = "token";

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveToken(request);
        if (token.isBlank()) {
            log.warn("缺少 token uri={}", request.getRequestURI());
            throw new BizException(ErrorCode.E_UNAUTHORIZED);
        }
        LoginUser user = tokenService.verify(token);
        AuthContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程复用，必须清理，否则会串号到下一个请求
        AuthContext.clear();
    }

    /**
     * 解析 token：Header 优先，其次查询参数（SSE）。
     *
     * @param request 当前请求
     * @return token 字符串，找不到返回空串
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String fromHeader = header.substring(BEARER_PREFIX.length()).trim();
            if (!fromHeader.isEmpty()) {
                return fromHeader;
            }
        }
        String fromQuery = request.getParameter(TOKEN_QUERY_PARAM);
        return fromQuery == null ? "" : fromQuery.trim();
    }
}
