package com.aittest.generation.controller;

import com.aittest.auth.LoginUser;
import com.aittest.auth.TokenService;
import com.aittest.common.ApiResponse;
import com.aittest.common.BizException;
import com.aittest.common.ErrorCode;
import com.aittest.common.enums.TaskScene;
import com.aittest.common.enums.TaskStatus;
import com.aittest.generation.domain.GenTask;
import com.aittest.generation.dto.GenTaskCreateReq;
import com.aittest.generation.dto.GenTaskVO;
import com.aittest.generation.service.GenTaskService;
import com.aittest.generation.service.ProgressPublisher;
import com.aittest.generation.service.SseEmitterRegistry;
import com.aittest.generation.service.SseErrorResponder;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务接口：创建、查询、取消、SSE 订阅。
 *
 * <p><b>SSE 端点的鉴权在这里做，不走 {@code AuthInterceptor}</b>（§4.3）：
 * 浏览器的 {@code EventSource} <b>无法自定义请求头</b>，只能把 token 放查询串，
 * 所以该路径进了 {@code WebMvcConfig.AUTH_WHITELIST}，改由本类调
 * {@code TokenService.verify(token)}。<b>校验没少，只是换了地方</b>——
 * 把它从白名单摘掉会得到"连上就 401"，而浏览器控制台只报一个不带上下文的
 * {@code EventSource error}，看不出是鉴权问题。</p>
 *
 * <p><b>因此本类禁止使用 {@code AuthContext}</b>：白名单路径上拦截器不执行，
 * ThreadLocal 里没有用户，调 {@code requireUserId()} 必抛 40101。所有用户身份
 * 一律来自 {@code TokenService.verify(token)} 的返回值（B2）。</p>
 *
 * <p><b>响应一律显式 VO</b>（§4.4），不返回实体。</p>
 *
 * <p><b>SSE 订阅失败不抛异常</b>（D-35，替代 D-29 的「无 token 必须 401」）：
 * 本类声明了 {@code produces = text/event-stream}，{@code @RestControllerAdvice} 想吐 JSON
 * 时会内容协商失败（{@code HttpMediaTypeNotAcceptableException}），客户端最终只看到
 * <b>500 空 body</b>；而浏览器 {@code EventSource} 既带不了自定义 header、也读不到
 * 非 200 响应的 body。所以 {@code stream()} 的显式契约是
 * <b>HTTP 200 + {@code event: error} 帧</b>，统一交给
 * {@link SseErrorResponder} 写出。响应体字段与 {@code ApiResponse} 完全一致，
 * 前端按帧内 {@code code} 分支（40101 鉴权 / 40301 归属 / 40401 不存在）。</p>
 *
 * @author 寇豆码
 */
@RestController
@RequestMapping("/api/tasks")
public class GenTaskController {

    private static final Logger log = LoggerFactory.getLogger(GenTaskController.class);

    private final GenTaskService genTaskService;

    private final ProgressPublisher progressPublisher;

    private final SseEmitterRegistry sseEmitterRegistry;

    private final TokenService tokenService;

    private final SseErrorResponder sseErrorResponder;

    public GenTaskController(GenTaskService genTaskService,
                             ProgressPublisher progressPublisher,
                             SseEmitterRegistry sseEmitterRegistry,
                             TokenService tokenService,
                             SseErrorResponder sseErrorResponder) {
        this.genTaskService = genTaskService;
        this.progressPublisher = progressPublisher;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.tokenService = tokenService;
        this.sseErrorResponder = sseErrorResponder;
    }

    /**
     * 创建任务。<b>立即返回 taskId</b>，不等执行（验收①）。
     *
     * @param req 入参
     * @return {@code {taskId, taskNo}}
     */
    @PostMapping
    public ApiResponse<Map<String, String>> create(@Valid @RequestBody GenTaskCreateReq req) {
        TaskScene scene = parseScene(req.scene());
        Long taskId = genTaskService.create(scene, req.docId(), req.provider(), req.model(),
                req.unitRefs(), req.testPointIds());
        GenTask task = genTaskService.require(taskId);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskId", String.valueOf(taskId));
        data.put("taskNo", task.getTaskNo());
        return ApiResponse.ok(data);
    }

    /**
     * 查询任务详情。
     *
     * @param id 任务 ID
     * @return VO
     */
    @GetMapping("/{id}")
    public ApiResponse<GenTaskVO> get(@PathVariable("id") Long id) {
        return ApiResponse.ok(GenTaskVO.of(genTaskService.require(id)));
    }

    /**
     * 取消任务：未认领的子项置 {@code SKIPPED}，已在跑的那条跑完它自己（不强杀）。
     *
     * @param id 任务 ID
     * @return {@code {skipped}}
     */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable("id") Long id) {
        int skipped = genTaskService.cancel(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skipped", skipped);
        return ApiResponse.ok(data);
    }

    /**
     * SSE 订阅任务进度。
     *
     * <p><b>首帧一定是 {@code snapshot}</b>（§4.3）：客户端据此渲染，不依赖增量拼装。
     * 断线重连时服务端<b>先推 snapshot 再续推增量</b>——单机内存不保存事件历史，
     * 用 DB 快照重建是刻意的简化。</p>
     *
     * <p><b>全部校验通过之后才 register / pushSnapshot</b>（B2）：旧实现在鉴权之前就把
     * emitter 放进注册表，而 {@code requireUserId()} 又在最后抛 40101——异常发生时
     * emitter 已入表却没返回给容器，三条清理回调永不触发，连接永久滞留。</p>
     *
     * <p><b>归属隔离（I9）</b>：{@code created_by} 非空且不等于当前用户时拒绝订阅，
     * 否则任何合法 token 都能订阅任意 taskId。历史任务 {@code created_by} 全为 NULL，
     * 因此 NULL 视为"无归属"，放行（否则已有任务全部订阅失败）。</p>
     *
     * <p><b>已终态任务订阅完快照立即关流</b>：{@code ProgressPublisher.finish()} 里的
     * {@code complete(taskId)} 只关闭"当时在线"的那批连接；任务终态之后才订阅的新
     * emitter 不会再收到任何后续事件，若留着就会一直挂到
     * {@code SseEmitterRegistry.TIMEOUT_MS}(30min)。这里只在终态分支提前关，
     * <b>正常执行中的订阅不受影响</b>。</p>
     *
     * <p><b>三类拒绝都返回 HTTP 200 + {@code event: error} 帧，不抛异常</b>（D-35）：
     * 缺 token（40101）/ 无权订阅他人任务（40301）/ 任务不存在（40401）。
     * 拒绝路径上<b>绝不调用 {@code sseEmitterRegistry.register}</b>——emitter 一旦入表
     * 却没返回给容器，三条清理回调永不触发，连接要挂满 30 分钟才被回收。</p>
     *
     * <p><b>为什么不抛 {@code BizException}</b>：本方法声明了
     * {@code produces = text/event-stream}，异常处理器要吐 JSON 时内容协商失败 →
     * {@code HttpMediaTypeNotAcceptableException}，客户端只看到 <b>500 空 body</b>，
     * 分不清"没登录"和"服务崩了"；而 {@code EventSource} 也读不到非 200 的 body。
     * 详见 {@link SseErrorResponder} 的类注释。</p>
     *
     * @param id    任务 ID
     * @param token JWT（{@code EventSource} 带不了 header，只能走查询串）
     * @return SSE 流（失败时是一个只含单条 {@code error} 帧并已关流的流）
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") Long id,
                             @RequestParam(value = "token", required = false) String token) {
        // 1) 缺 token 短路：连验签都不做（先 verify 再判空是白跑一次 HMAC）。
        //    返回 error 帧而不是抛异常，见方法注释的 D-35 说明。
        if (token == null || token.isBlank()) {
            return reject(id, ErrorCode.E_UNAUTHORIZED, "缺少 token，无法订阅任务进度");
        }

        // 2) 验签 + 任务存在性 + 归属校验。
        //    这三步都抛 BizException（40102 / 40401 / 40301），统一在 catch 里翻成 error 帧，
        //    保证"任何一处拒绝"都走同一个出口，不会漏掉某个分支又退回 500 空 body。
        LoginUser user;
        GenTask task;
        try {
            user = tokenService.verify(token);
            task = genTaskService.require(id);
            if (task.getCreatedBy() != null && !task.getCreatedBy().equals(user.id())) {
                throw new BizException(ErrorCode.E_FORBIDDEN, "无权订阅他人的任务进度");
            }
        } catch (BizException ex) {
            return reject(id, ex.getErrorCode(), ex.getMessage());
        }

        // 3) 全部校验通过：注册 emitter、推首帧快照、返回给容器
        //    register 只出现在这一行之后 —— 拒绝路径上没有 emitter 入表。
        SseEmitter emitter = sseEmitterRegistry.register(id);
        progressPublisher.pushSnapshot(id);

        // 4) 终态任务不会有后续事件，推完快照即关，避免新 emitter 挂到 30min 超时。
        //    判据用枚举的 isTerminal()，与 GenTaskWorker.isFinalized 同一口径。
        if (TaskStatus.valueOf(task.getStatus()).isTerminal()) {
            sseEmitterRegistry.complete(id);
            log.debug("[SSE] 任务已终态，订阅完快照即关闭 taskId={} status={}", id, task.getStatus());
        }
        log.debug("[SSE] 新订阅 taskId={} 用户={}", id, user.id());
        return emitter;
    }

    /**
     * 把一次订阅拒绝写成 SSE 的 {@code error} 帧并关流（D-35）。
     *
     * <p><b>超时取 {@code 0L}</b>：反正写完立即 {@code complete()}，不超时就不会有
     * 定时器白白挂着一个已死的连接。</p>
     *
     * <p><b>不复用注册表</b>：这个 emitter 从未进 {@code SseEmitterRegistry}，
     * 由容器在本次请求的返回值处理里 {@code initialize} 后直接关掉。</p>
     *
     * @param id      任务 ID，仅用于日志
     * @param code    业务错误码
     * @param message 面向用户的中文提示
     * @return 只含单条 {@code error} 帧、写完后立即关流的 emitter
     */
    private SseEmitter reject(Long id, ErrorCode code, String message) {
        // 兜底：BizException 的 message 来自构造参数，理论上非空；真为空时退回错误码默认文案，
        // 免得前端 toast 出一片空白。
        String text = (message == null || message.isBlank()) ? code.getMessage() : message;
        log.debug("[SSE] 订阅被拒，返回 error 帧 taskId={} code={} 原因={}",
                id, code.getCode(), text);
        // 0L = 不超时：立即关流，不需要 30min 超时兜底
        SseEmitter rejected = new SseEmitter(0L);
        sseErrorResponder.writeError(rejected, code, text);
        return rejected;
    }

    /** 场景字符串 → 枚举，不认识的给 40001 而不是静默当默认值。 */
    private TaskScene parseScene(String scene) {
        for (TaskScene s : TaskScene.values()) {
            if (s.code().equals(scene)) {
                return s;
            }
        }
        throw new BizException(ErrorCode.E_PARAM_INVALID,
                "不支持的任务场景：" + scene + "，可选值：TESTPOINT_EXTRACT / CASE_GENERATE");
    }
}
