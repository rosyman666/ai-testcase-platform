package com.aittest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册鉴权拦截器与 CORS。
 *
 * <p><b>白名单只在这里声明</b>（见 {@link #AUTH_WHITELIST}）。白名单漏配的后果不是"功能不可用"
 * 而是"死锁"——例如登录页要靠 {@code /api/config/public} 渲染下拉框，如果它被拦截，
 * 登录页会 401 → 跳登录页 → 再拿字典 → 再 401，用户永远进不去。所以这几个路径单独列成常量，
 * 谁要加白名单必须改这个数组，评审时一眼可见。</p>
 *
 * @author 寇豆码
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 鉴权白名单：未登录可访问。**新增条目必须写清理由**。
     *
     * <ul>
     *   <li>{@code /api/health}——健康检查，运维探针不带 token；</li>
     *   <li>{@code /api/auth/login}——登录本身；</li>
     *   <li>{@code /api/config/public}、{@code /api/dict/**}——登录页渲染所需字典，
     *       拦截会导致"登录页拿不到字典"的死锁；</li>
     *   <li>{@code /api/tasks/{id}/stream}——<b>SSE 订阅</b>。浏览器的 {@code EventSource}
     *       <b>无法自定义请求头</b>，只能把 token 放查询串，因此该路径必须放行
     *       {@code Authorization} 校验，改由 {@code GenTaskStreamController} 自己调
     *       {@code TokenService.verify(token)}（见 §4.3）。<b>注意这不是"免鉴权"</b>——
     *       校验没少，只是换了地方；把它从白名单摘掉会得到"连上就 401"，
     *       而浏览器控制台只报一个不带上下文的 EventSource error；</li>
     *   <li>{@code /error}、{@code /actuator/**}、{@code /v3/api-docs/**}、{@code /swagger-ui/**}
     *       ——容器错误页、运维与接口文档。</li>
     * </ul>
     */
    public static final String[] AUTH_WHITELIST = {
            "/api/health",
            "/api/auth/login",
            "/api/config/public",
            "/api/dict/**",
            "/api/tasks/*/stream",
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    /** 需要鉴权的路径。 */
    private static final String[] AUTH_PROTECTED = {"/api/**"};

    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(AUTH_PROTECTED)
                .excludePathPatterns(AUTH_WHITELIST)
                .order(0);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 开发期前端跑在 5173；生产同源反代，不需要 CORS
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(1800);
    }
}
