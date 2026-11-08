package com.aittest.generation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/tasks/{id}/stream} 的鉴权与归属校验契约锁（闭合 t06negative NEG-7）。
 *
 * <p><b>为什么必须用单测而不是黑盒</b>：{@code gen_task.created_by} 有外键
 * {@code fk_gen_task_created_by → app_user.id}，而验收库里只有 admin 一个用户。
 * 构造"别人的任务"只能插 {@code app_user} 行或关 FK 检查——共享库上出过事故，
 * 两条路都不走。纯 Mockito 直接构造 controller，不受库约束。</p>
 *
 * <p><b>本类守的四条纪律</b>（任一条回退都会造成真实故障）：</p>
 * <ol>
 *   <li>空 token <b>短路</b>，不先 verify（否则白跑一次验签）；</li>
 *   <li>归属不匹配 → {@code E_FORBIDDEN(40301)}，且 <b>{@code register} 从未被调用</b>
 *       ——任何"先注册后校验"的写法都会漏 emitter，挂到 30min 超时；</li>
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

    @Mock
    private GenTaskService genTaskService;

    @Mock
    private ProgressPublisher progressPublisher;

    @Mock
    private SseEmitterRegistry sseEmitterRegistry;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private GenTaskController controller;

    @BeforeEach
    void setUp() {
        // 通过校验后 register 必须返回一个非 null emitter 供控制器回给容器。
        // 空 token / 归属不匹配两条用例根本走不到 register，故用 lenient 避免
        // StrictStubs 的 UnnecessaryStubbingException 误报。
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

    @Test
    @DisplayName("空 token：40101 短路，且 tokenService.verify 一次都不调")
    void blankTokenRejectedWithoutVerify() {
        BizException ex = assertThrows(BizException.class, () -> controller.stream(TASK_ID, "  "));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_UNAUTHORIZED);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40101);
        // 空 token 没资格触发验签——先 verify 再判空是白跑一次 HMAC
        verifyNoInteractions(tokenService);
        verifyNoInteractions(genTaskService, sseEmitterRegistry, progressPublisher);
    }

    @Test
    @DisplayName("token 为 null：同样 40101 短路，不 verify、不注册")
    void nullTokenRejectedWithoutVerify() {
        BizException ex = assertThrows(BizException.class, () -> controller.stream(TASK_ID, null));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_UNAUTHORIZED);
        verifyNoInteractions(tokenService);
        verifyNoInteractions(sseEmitterRegistry);
    }

    @Test
    @DisplayName("归属不匹配（verify=2，createdBy=1）→ 40301，且 register 从未被调用")
    void ownerMismatchForbiddenAndNeverRegisters() {
        when(tokenService.verify(TOKEN)).thenReturn(new LoginUser(2L, "other", "别人"));
        when(genTaskService.require(TASK_ID)).thenReturn(task(1L, TaskStatus.RUNNING));

        BizException ex = assertThrows(BizException.class, () -> controller.stream(TASK_ID, TOKEN));

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.E_FORBIDDEN);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(40301);
        // 关键：校验全部通过之前绝不 register，否则 emitter 入表却不返回，连接泄漏
        verify(sseEmitterRegistry, never()).register(any());
        verify(progressPublisher, never()).pushSnapshot(any());
        verify(sseEmitterRegistry, never()).complete(any());
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
