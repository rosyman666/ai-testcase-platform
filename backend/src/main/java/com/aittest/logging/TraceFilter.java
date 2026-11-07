package com.aittest.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：为每个请求生成（或透传）traceId，写入 MDC 并回写响应头。
 *
 * <p>traceId 是排障的一等公民：统一响应体 {@code traceId} 字段、日志 pattern、响应头
 * {@code X-Trace-Id} 三处必须一致，前端提示用户"请提供 traceId"时才能对上日志。</p>
 *
 * @author 寇豆码
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    /** MDC 中的 traceId 键名，logback pattern 通过 %X{traceId} 引用。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** 响应头名称：前端/网关据此报错定位。 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** traceId 长度（16 位十六进制，足够单机排查且不冗长）。 */
    private static final int TRACE_ID_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程复用，必须清理，否则 traceId 会串到下一个请求
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /**
     * 优先复用上游（网关/前端）传入的 traceId，缺省时本地生成。
     *
     * @param request 当前请求
     * @return 非空的 traceId
     */
    private String resolveTraceId(HttpServletRequest request) {
        String inbound = request.getHeader(TRACE_HEADER);
        if (inbound != null) {
            String trimmed = inbound.trim();
            // 长度设上限，防止恶意超长 header 污染日志
            if (!trimmed.isEmpty() && trimmed.length() <= 64) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
    }
}
