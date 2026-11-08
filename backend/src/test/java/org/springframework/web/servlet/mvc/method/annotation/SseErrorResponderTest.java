package org.springframework.web.servlet.mvc.method.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.aittest.common.ApiResponse;
import com.aittest.common.ErrorCode;
import com.aittest.generation.service.SseErrorResponder;
import com.aittest.logging.TraceFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * {@link SseErrorResponder} 的字节级契约锁（D-35）。
 *
 * <p><b>为什么本类放在 {@code org.springframework.web.servlet.mvc.method.annotation} 包下</b>：
 * 要断言"未 initialize 时 send 进缓冲、initialize 时 flush 并关流"这条真实链路，
 * 必须调 {@link ResponseBodyEmitter#initialize(ResponseBodyEmitter.Handler)}，
 * 而它在 spring-webmvc 里是 <b>package-private</b>（{@code javap -p} 已确认：
 * {@code synchronized void initialize(Handler)}；{@code ResponseBodyEmitter.Handler}
 * 本身也是包私有接口）。测试类放进同一包是访问它的唯一方式，
 * <b>不改生产代码可见性</b>。这不是越界断言 Spring 内部实现——它锁的正是本项目的
 * 生产方式：controller 在 return emitter <b>之前</b>就写完帧，只能靠 early-send 缓冲生效。</p>
 *
 * <p><b>从字节码核实过的 flush / 关流顺序</b>（spring-webmvc 6.1.14
 * {@code ResponseBodyEmitter.initialize}）：先 {@code handler = h}，再
 * {@code sendInternal(earlySendAttempts)}，再 {@code earlySendAttempts.clear()}；
 * 随后若 {@code complete} 为真则按 {@code failure} 调
 * {@code handler.completeWithError(failure)} 或 {@code handler.complete()}，
 * 否则才注册 timeout / error / completion 三个回调。
 * 所以「HTTP 200 + early send + complete」有实现保证，不是碰运气。</p>
 *
 * <p><b>帧的三个数据段</b>（{@code SseEventBuilderImpl.build()} 的真实产物）：
 * <ol>
 *   <li>{@code "event:error\ndata:"} —— 文本前缀，事件名就在这里，同时证明不发 {@code id:} 行；</li>
 *   <li>{@link ApiResponse} 对象 + {@code APPLICATION_JSON} —— Jackson 序列化成 JSON 对象；</li>
 *   <li>{@code "\n\n"} —— SSE 事件结束空行。</li>
 * </ol>
 * 拼起来是 {@code event:error} / {@code data:{"code":...}} / 空行，前端在
 * {@code addEventListener('error')} 里 {@code JSON.parse(e.data)} 直接可用。</p>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class SseErrorResponderTest {

    private static final String TRACE_ID = "trace-d35-0001";

    /** 容器侧回调：生产环境由 {@code ResponseBodyEmitterReturnValueHandler} 的实现接管。 */
    @Mock
    private ResponseBodyEmitter.Handler handler;

    private final SseErrorResponder responder = new SseErrorResponder();

    /** {@code handler.send(Set)} 每次被调用时的<b>防御性拷贝</b>（LinkedHashSet 保序）。 */
    private final List<List<ResponseBodyEmitter.DataWithMediaType>> sentFrames = new ArrayList<>();

    @AfterEach
    void clearMdc() {
        // traceId 走 ThreadLocal，不清会串到下一个用例
        MDC.clear();
        sentFrames.clear();
    }

    /**
     * 装上"记录每一帧"的应答。只在真正会写帧的用例里调用（避免 StrictStubs 误报）。
     */
    private void recordSentFrames() throws IOException {
        doAnswer(invocation -> {
            Set<ResponseBodyEmitter.DataWithMediaType> frame = invocation.getArgument(0);
            // 必须拷贝，不能只存引用：initialize() flush 完 earlySendAttempts 后会
            // clear() **同一个集合实例**（字节码里 sendInternal(...) 紧跟 clear()）。
            // 第一版用 ArgumentCaptor 直接存引用，断言时只剩空集——坑就在这里。
            sentFrames.add(new ArrayList<>(frame));
            return null;
        }).when(handler).send(anySet());
    }

    /** 断言"只写了一帧"，并返回这一帧的三段数据。 */
    private List<ResponseBodyEmitter.DataWithMediaType> singleFrame() throws IOException {
        verify(handler, times(1)).send(anySet());
        assertThat(sentFrames).as("error 帧只应写一次").hasSize(1);
        return sentFrames.get(0);
    }

    /** 从三段里取出载荷那一段（data 是 {@link ApiResponse} 实例）。 */
    private static ApiResponse<?> payloadOf(List<ResponseBodyEmitter.DataWithMediaType> parts) {
        return parts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(ApiResponse.class::isInstance)
                .map(ApiResponse.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("帧里没有 ApiResponse 载荷段：" + parts));
    }

    @Test
    @DisplayName("帧内容：event:error + data:{code,message,data=null,traceId} + 空行，随后 complete")
    void writesErrorFrameThenCompletesOnInitialize() throws Exception {
        recordSentFrames();
        MDC.put(TraceFilter.MDC_TRACE_ID, TRACE_ID);

        // 生产方式一模一样：controller 先 new SseEmitter(0L) 再 writeError，最后才 return
        SseEmitter emitter = new SseEmitter(0L);
        responder.writeError(emitter, ErrorCode.E_UNAUTHORIZED, "缺少 token，无法订阅任务进度");

        // 未 initialize：帧还躺在 earlySendAttempts 缓冲里，容器一个字节都没收到
        assertThat(sentFrames).as("未 initialize 时不该有任何 send").isEmpty();

        // 容器接手（真实链路是 ResponseBodyEmitterReturnValueHandler 调 initialize）
        emitter.initialize(handler);

        List<ResponseBodyEmitter.DataWithMediaType> parts = singleFrame();
        assertThat(parts).as("event: 前缀 + 载荷 + 结束空行，正好三段").hasSize(3);

        // 段 1：文本前缀。事件名 error 就在这里，同时证明"不发 id: 行"
        assertThat(parts.get(0).getData()).isEqualTo("event:error\ndata:");
        assertThat(parts.get(0).getMediaType()).isNotNull();
        assertThat(parts.get(0).getMediaType().getType()).isEqualTo("text");
        assertThat(parts.get(0).getMediaType().getSubtype()).isEqualTo("plain");

        // 段 2：载荷对象，按 APPLICATION_JSON 交给 Jackson（是 JSON 对象，不是字符串）
        assertThat(parts.get(1).getMediaType()).isEqualTo(MediaType.APPLICATION_JSON);
        ApiResponse<?> payload = payloadOf(parts);
        assertThat(payload.getCode()).isEqualTo(40101);
        assertThat(payload.getMessage()).isEqualTo("缺少 token，无法订阅任务进度");
        assertThat(payload.getData()).as("失败响应 data 恒为 null").isNull();
        assertThat(payload.getTraceId()).as("traceId 取自 MDC，与 ApiResponse 同源").isEqualTo(TRACE_ID);

        // 段 3：SSE 事件结束空行（少了它浏览器不会派发事件）
        assertThat(parts.get(2).getData()).isEqualTo("\n\n");

        // 写完即正常关流——不是错误关流
        verify(handler, times(1)).complete();
        verify(handler, never()).completeWithError(any());
        // 已 complete 的 emitter 不会再挂超时/错误/完成回调（否则白留一个 30min 定时器）
        verify(handler, never()).onTimeout(any());
        verify(handler, never()).onError(any());
        verify(handler, never()).onCompletion(any());
    }

    @Test
    @DisplayName("没有 MDC traceId 时：字段仍在，值为空串（不退化成 null、不丢字段）")
    void traceIdFallsBackToEmptyStringWithoutMdc() throws Exception {
        recordSentFrames();
        MDC.remove(TraceFilter.MDC_TRACE_ID);

        SseEmitter emitter = new SseEmitter(0L);
        responder.writeError(emitter, ErrorCode.E_FORBIDDEN, "无权订阅他人的任务进度");
        emitter.initialize(handler);

        List<ResponseBodyEmitter.DataWithMediaType> parts = singleFrame();
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0).getData()).isEqualTo("event:error\ndata:");

        // 错误码决定前端分支，与 message 无关：40301 走归属提示
        ApiResponse<?> payload = payloadOf(parts);
        assertThat(payload.getCode()).isEqualTo(40301);
        assertThat(payload.getData()).isNull();
        assertThat(payload.getTraceId()).as("非 Web 线程退化为空串，但字段必须在").isNotNull().isEmpty();

        verify(handler, times(1)).complete();
        verify(handler, never()).completeWithError(any());
    }

    @Test
    @DisplayName("写帧失败（客户端已断开）→ 不外抛，改用 completeWithError 关流")
    void sendFailureIsNotRethrownAndClosesWithError() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        // 容器已接管：send 直达 handler，不再进 earlySendAttempts 缓冲
        emitter.initialize(handler);

        IOException boom = new IOException("Broken pipe");
        doThrow(boom).when(handler).send(anySet());

        // 关键：异常不能冒到 DispatcherServlet，否则又是一次 500
        assertThatCode(() -> responder.writeError(emitter, ErrorCode.E_FORBIDDEN, "无权订阅他人的任务进度"))
                .doesNotThrowAnyException();

        verify(handler, times(1)).send(anySet());
        // 同一个异常实例透传给容器，保留原始原因便于排障
        verify(handler, times(1)).completeWithError(boom);
        verify(handler, never()).complete();
    }
}
