package com.aittest.generation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aittest.auth.LoginUser;
import com.aittest.auth.TokenService;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.service.GenTaskService;
import com.aittest.generation.service.ProgressPublisher;
import com.aittest.generation.service.SseEmitterRegistry;
import com.aittest.generation.service.SseErrorResponder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/tasks/{id}/stream} 的鉴权、归属与错误响应契约锁
 * （闭合 t06negative NEG-7，按 D-35 重写）。
 *
 * <p><b>契约已由「抛异常」改为「HTTP 200 + {@code event: error} 帧」</b>（D-35 推翻 D-29）：
 * 本端点声明 {@code produces = text/event-stream}，抛 {@code BizException} 会让
 * {@code @RestControllerAdvice} 内容协商失败，客户端只看到 500 空 body；而
 * {@code EventSource} 读不到非 200 的 body，用户分不清"没登录"和"服务崩了"。
 * 因此 controller 现在把拒绝交给 {@link SseErrorResponder#writeError}，
 * 本类断言的是<b>"谁被写、写了什么码、注册表有没有被污染"</b>，
 * 帧内容的字节级形状由 {@code SseErrorResponderTest} 负责。</p>
 *
 * <p><b>为什么必须用单测而不是黑盒</b>：{@code gen_task.created_by} 有外键
 * {@code fk_gen_task_created_by → app_user.id}，而验收库里只有 admin 一个用户。
 * 构造"别人的任务"只能插 {@code app_user} 行或关 FK 检查——共享库上出过事故，
 * 两条路都不走。纯 Mockito 直接构造 controller，不受库约束。</p>
 *
 * <p><b>本类守的五条纪律</b>（任一条回退都会造成真实故障）：</p>
 * <ol>
 *   <li>空 / null token <b>短路</b>，不先 verify（否则白跑一次验签）；</li>
 *   <li>三类拒绝一律<b>返回非 null 的 emitter 且不抛</b>——抛了就退回 500 空 body；</li>
 *   <li>拒绝路径上 <b>{@code register} 从未被调用</b>——任何"先注册后校验"的写法
 *       都会漏 emitter，挂到 30min 超时；</li>
 *   <li>历史任务 {@code created_by=null} 必须放行（否则已有任务全部订阅失败）；</li>
 *   <li>已终态任务推完快照即 {@code complete}，不占着连接。</li>
 * </ol>
 *
 * @author 寇豆码
 */
@ExtendWith(MockitoExtension.class)
class GenTaskControllerStreamAuthTest {

    private static final Long TASK_ID = 7300L;

    private static final Long DOC_ID = 9001L;

    private static final String TOKEN = "header.payload.signature";

    private static final String MISSING_TOKEN_MSG = "缺少 token，无法订阅任务进度";

    private static final String FORBIDDEN_MSG = "无权订阅他人的任务进度";

    private static final String NOT_FOUND_MSG = "任务不存在";

    @Mock
    private GenTaskService genTaskService;

    @Mock
    private ProgressPublisher progressPublisher;

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    @Mock
    private TokenService tokenService;

    @Mock
    private SseErrorResponder sseErrorResponder;

    @InjectMocks
    private GenTaskController controller;

    @BeforeEach
    void setUp() {
        // 通过校验后 register 必须返回一个非 null emitter 供控制器回给容器。
        // 拒绝路径的用例根本走不到 register，故用 lenient 避免 StrictStubs 的
        // UnnecessaryStubbingException 误报。
        lenient().when(sseEmitterRegistry.register(TASK_ID)).thenReturn(new SseEmitter(60_000L));
    }

    private static GenTask task(Long createdBy, TaskStatus status) {
        GenTask t = new GenTask();
        t.setId(TASK_ID);
        t.setTaskNo("T20261108-000300");
        t.setScene(TaskScene.TESTPOINT_EXTRACT.code());
        t.setDocId(DOC_ID);
        t.setStatus(status.name());
        t.setCreatedBy(createdBy);
        return t;
    }

    /** 断言"走了 error 帧出口"：{@code writeError} 恰好一次、码正确、拿到的 emitter 非 null。 */
    private SseEmitter captureRejectedEmitter(ErrorCode expectedCode) {
        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(sseErrorResponder, times(1))
                .writeError(emitterCaptor.capture(), eq(expectedCode), anyString());
        SseEmitter rejected = emitterCaptor.getValue();
        assertThat(rejected)
                .as("拒绝时必须回一个真 emitter（容器要拿它写帧），不能是 null")
                .isNotNull();
        return rejected;
    }

    /** 断言"没有污染注册表"：register / pushSnapshot / complete 全都没被碰过。 */
    private void verifyRegistryUntouched() {
        verify(sseEmitterRegistry, never()).register(any());
        verify(progressPublisher, never()).pushSnapshot(any());
        verify(sseEmitterRegistry, never()).complete(any());
    }

    @Test
    @DisplayName("空白 token：回 40101 error 帧、不抛异常，且 tokenService.verify 一次都不调")
    void blankTokenReturnsErrorFrameWithoutVerify() {
        SseEmitter returned = controller.stream(TASK_ID, "  ");

        assertThat(returned).as("缺 token 也必须返回 emitter，不能抛异常").isNotNull();
        captureRejectedEmitter(ErrorCode.E_UNAUTHORIZED);
        verify(sseErrorResponder, times(1))
                .writeError(any(), eq(ErrorCode.E_UNAUTHORIZED), eq(MISSING_TOKEN_MSG));
        // 空 token 没资格触发验签——先 verify 再判空是白跑一次 HMAC
        verifyNoInteractions(tokenService);
        verifyRegistryUntouched();
        verifyNoInteractions(genTaskService);
    }

    @Test
    @DisplayName("token 为 null：同样回 40101 error 帧，不 verify、不注册、不抛")
    void nullTokenReturnsErrorFrameWithoutVerify() {
        SseEmitter returned = controller.stream(TASK_ID, null);

        assertThat(returned).isNotNull();
        captureRejectedEmitter(ErrorCode.E_UNAUTHORIZED);
        verifyNoInteractions(tokenService);
        verifyRegistryUntouched();
    }

    @Test
    @DisplayName("归属不匹配（verify=2，createdBy=1）→ 40301 error 帧，且 register 从未被调用")
    void ownerMismatchReturnsForbiddenFrameAndNeverRegisters() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(2L, "other", "别人"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(1L, TaskStatus.RUNNING));

        SseEmitter returned = controller.stream(TASK_ID, TOKEN);

        assertThat(returned).isNotNull();
        captureRejectedEmitter(ErrorCode.E_FORBIDDEN);
        verify(sseErrorResponder, times(1))
                .writeError(any(), eq(ErrorCode.E_FORBIDDEN), eq(FORBIDDEN_MSG));
        // 关键：校验全部通过之前绝不 register，否则 emitter 入表却不返回，连接泄漏
        verifyRegistryUntouched();
    }

    @Test
    @DisplayName("任务不存在（require 抛 40401）→ 40401 error 帧，且 register 从未被调用")
    void missingTaskReturnsNotFoundFrameAndNeverRegisters() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(1L, "admin", "管理员"));
        when(genTaskService.require(TASK_ID))
                .thenThrow(new BizException(ErrorCode.E_NOT_FOUND, NOT_FOUND_MSG));

        SseEmitter returned = controller.stream(TASK_ID, TOKEN);

        assertThat(returned).isNotNull();
        captureRejectedEmitter(ErrorCode.E_NOT_FOUND);
        verify(sseErrorResponder, times(1))
                .writeError(any(), eq(ErrorCode.E_NOT_FOUND), eq(NOT_FOUND_MSG));
        verifyRegistryUntouched();
    }

    @Test
    @DisplayName("token 过期/被篡改（verify 抛 40102）→ 40102 error 帧，不抛、不注册")
    void invalidTokenReturnsTokenInvalidFrameAndNeverRegisters() {
        when(tokenService.verify(TOKEN))
                .thenThrow(new BizException(ErrorCode.E_TOKEN_INVALID));

        SseEmitter returned = controller.stream(TASK_ID, TOKEN);

        assertThat(returned).isNotNull();
        captureRejectedEmitter(ErrorCode.E_TOKEN_INVALID);
        // 验签失败就不该再查任务，也绝不能注册
        verifyNoInteractions(genTaskService);
        verifyRegistryUntouched();
    }

    @Test
    @DisplayName("归属匹配（verify=1，createdBy=1）→ 放行，register 1 次 + pushSnapshot 1 次")
    void ownerMatchRegistersAndPushesSnapshot() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(1L, "admin", "管理员"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(1L, TaskStatus.RUNNING));

        SseEmitter emitter = controller.stream(TASK_ID, TOKEN);

        assertThat(emitter).isNotNull();
        verify(sseEmitterRegistry, times(1)).register(TASK_ID);
        verify(progressPublisher, times(1)).pushSnapshot(TASK_ID);
        // 执行中的任务不能被提前关流
        verify(sseEmitterRegistry, never()).complete(any());
        // 放行路径上一个 error 帧都不能写
        verify(sseErrorResponder, never()).writeError(any(), any(), any());
    }

    @Test
    @DisplayName("历史任务 createdBy=null → 放行（NULL 视为无归属，不锁死老任务）")
    void legacyNullOwnerIsAllowed() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(1L, "admin", "管理员"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(null, TaskStatus.RUNNING));

        SseEmitter emitter = controller.stream(TASK_ID, TOKEN);

        assertThat(emitter).isNotNull();
        verify(sseEmitterRegistry, times(1)).register(TASK_ID);
        verify(progressPublisher, times(1)).pushSnapshot(TASK_ID);
        verify(sseEmitterRegistry, never()).complete(any());
        verify(sseErrorResponder, never()).writeError(any(), any(), any());
    }

    @Test
    @DisplayName("已终态任务（SUCCESS）→ 推完快照立即 complete，防新 emitter 挂 30min")
    void terminalTaskCompletesAfterSnapshot() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(1L, "admin", "管理员"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(1L, TaskStatus.SUCCESS));

        SseEmitter emitter = controller.stream(TASK_ID, TOKEN);

        assertThat(emitter).isNotNull();
        verify(sseEmitterRegistry, times(1)).register(TASK_ID);
        verify(progressPublisher, times(1)).pushSnapshot(TASK_ID);
        // finish() 里的 complete 只关"当时在线"的连接；终态后新订阅必须在这里关
        verify(sseEmitterRegistry, times(1)).complete(TASK_ID);
        verify(sseErrorResponder, never()).writeError(any(), any(), any());
    }

    @Test
    @DisplayName("终态任务 + 归属匹配：顺序是先 pushSnapshot 再 complete（快照不能被关流吞掉）")
    void snapshotPushedBeforeCompleteForTerminalTask() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(1L, "admin", "管理员"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(1L, TaskStatus.CANCELED));

        InOrder inOrder = inOrder(sseEmitterRegistry, progressPublisher);
        controller.stream(TASK_ID, TOKEN);

        inOrder.verify(sseEmitterRegistry).register(TASK_ID);
        inOrder.verify(progressPublisher).pushSnapshot(TASK_ID);
        inOrder.verify(sseEmitterRegistry).complete(TASK_ID);
    }
}
