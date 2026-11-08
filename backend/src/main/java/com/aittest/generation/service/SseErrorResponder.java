package com.aittest.generation.service;

import com.aittest.common.ApiResponse;
import com.aittest.common.ErrorCode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把业务失败写成 SSE 的 {@code error} 帧并立即关流（D-35）。
 *
 * <p><b>为什么不抛异常</b>（D-35 的核心）：SSE 端点声明了
 * {@code produces = text/event-stream}，而浏览器 {@code EventSource} 带不了自定义
 * header、也<b>读不到非 200 响应的 body</b>。若这里抛 {@code BizException}，
 * {@code @RestControllerAdvice} 要吐 JSON 时内容协商失败 →
 * {@code HttpMediaTypeNotAcceptableException} → 客户端只看到 <b>500 空 body</b>，
 * 无法区分"没登录"和"服务崩了"。因此本端点的显式契约是
 * <b>HTTP 200 + {@code event: error} 帧</b>（替代 D-29 的「无 token 必须 401」）。</p>
 *
 * <p><b>载荷与 {@link ApiResponse} 完全一致</b>（{@code code / message / data / traceId}，
 * {@code data} 恒为 {@code null}）：直接复用 {@link ApiResponse#fail(ErrorCode, String)}，
 * 保证 traceId 取自 MDC 的同一来源（{@code TraceFilter.MDC_TRACE_ID}），
 * 不另造一套。前端按帧里的 {@code code} 区分
 * {@code 40101} 鉴权 / {@code 40301} 归属 / {@code 40401} 任务不存在 / 其余为执行失败。</p>
 *
 * <p><b>事件名用 {@code error}</b>：与任务执行失败时的 {@code error} 事件同名，
 * 靠载荷 {@code code} 区分。<b>不发 {@code id:} 行</b>（与现有 SSE 契约一致：
 * 服务端从不发 id，断线恢复靠"每次订阅先推 snapshot"）。</p>
 *
 * @author 寇豆码
 */
@Component
public class SseErrorResponder {

    private static final Logger log = LoggerFactory.getLogger(SseErrorResponder.class);

    /**
     * 把业务失败写成 SSE 的 {@code error} 帧并关流。HTTP 状态已由容器定为 200。
     *
     * <p><b>emitter 由调用方创建并传入</b>：这样单测可以先 {@code initialize(handler)}
     * 捕获帧内容，再断言；生产侧则 {@code new SseEmitter(0L)}（不超时，反正立即关流）。</p>
     *
     * <p><b>写帧失败不向外抛</b>：客户端可能已经断开，此时再抛只会让容器记一条
     * 无意义的异常日志；改为 {@code completeWithError} 交给容器收尾。</p>
     *
     * @param emitter 调用方创建、尚未 initialize 的 SSE emitter
     * @param code    业务错误码（决定载荷里的 {@code code} 与默认文案）
     * @param message 面向用户的中文提示
     */
    public void writeError(SseEmitter emitter, ErrorCode code, String message) {
        // 复用统一响应体：字段名、traceId 来源（MDC）与 /api/* 的 JSON 响应完全一致
        ApiResponse<Object> payload = ApiResponse.fail(code, message);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    // 传对象 + APPLICATION_JSON：由 Jackson 序列化成 JSON 对象，
                    // 不是字符串；前端 JSON.parse 直接可用。
                    .data(payload, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException ex) {
            // 客户端已断开等写失败：不向外抛，交给容器 completeWithError 收尾
            log.warn("[SSE] 写 error 帧失败，改为关闭连接 code={} 原因={}",
                    code.getCode(), ex.getMessage());
            emitter.completeWithError(ex);
        }
    }
}
