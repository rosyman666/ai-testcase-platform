package com.aittest.generation.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 连接注册表：{@code taskId → emitters}，心跳、断线清理、快照回放。
 *
 * <p><b>刻意的简化（不是缺陷）</b>：本类是<b>进程内</b> {@link ConcurrentHashMap}，
 * 单机部署下够用。多实例部署时必须换成 Redis Pub/Sub 或网关广播——见 §4.3「已知限制」。
 * 今天先付这个成本是过度设计：真正的分布式需求还没出现。</p>
 *
 * <p><b>为什么不在内存里存事件历史</b>：存了就得考虑淘汰、容量、实例重启后怎么补。
 * 而"断线重连能恢复进度"这件事<b>不需要</b>事件历史——{@code gen_task} 表里
 * {@code progress / done_items / total_items} 一直是权威值，重连时<b>先推 snapshot 再续推增量</b>
 * 即可（§4.3）。用 DB 快照重建比维护一份会漂的内存副本可靠得多。</p>
 *
 * <p><b>心跳 20s 一次</b>：网关与浏览器都会掐空闲连接，而任务一跑几十秒。心跳不是"防假死"，
 * 是"防被中间层当成空闲连接关掉"。</p>
 *
 * @author 寇豆码
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** SSE 连接超时（与 §4.3 一致：30 分钟，够跑完一个任务）。 */
    private static final long TIMEOUT_MS = 30L * 60 * 1000;

    /** taskId → 该任务的全部订阅连接。 */
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 注册一个订阅连接，并挂上完成/异常/超时三条清理路径。
     *
     * <p><b>三条清理路径缺一不可</b>：只挂 {@code onCompletion} 时，客户端拔网线走的是
     * {@code onError}，连接对象会永远留在列表里——下次推送 {@code send} 抛 {@code IOException}
     * 被吞掉，于是"推送静默失败"，而服务端日志里看起来一切正常。</p>
     *
     * @param taskId 任务
     * @return 注册好的 emitter（调用方直接返回给 Spring）
     */
    public SseEmitter register(Long taskId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(taskId, list);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        log.debug("[SSE] 注册连接 taskId={} 当前连接数={}", taskId, list.size());
        return emitter;
    }

    /**
     * 向某任务的全部订阅连接推一个事件。
     *
     * <p><b>推送失败只清理、不抛</b>：一个客户端断线不该让正在跑的任务收到异常。
     * 但<b>失败会打 DEBUG 日志</b>，排查"前端收不到事件"时从这里入手。</p>
     *
     * @param taskId  任务
     * @param event   事件名：{@code snapshot / progress / item / done / error / heartbeat}
     * @param payload 数据体（会被 Jackson 序列化成 JSON）
     */
    public void send(Long taskId, String event, Object payload) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException | IllegalStateException ex) {
                log.debug("[SSE] 推送失败，清理连接 taskId={} event={} 原因={}", taskId, event, ex.getMessage());
                list.remove(emitter);
            }
        }
        if (list.isEmpty()) {
            emitters.remove(taskId, list);
        }
    }

    /**
     * 关闭某任务的全部连接（任务进入终态后调用）。
     *
     * @param taskId 任务
     */
    public void complete(Long taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.complete();
            } catch (Exception ex) {
                log.debug("[SSE] 关闭连接异常 taskId={} 原因={}", taskId, ex.getMessage());
            }
        }
    }

    /**
     * 心跳：防网关/浏览器把空闲连接当死连接掐掉。
     *
     * <p>只对<b>仍有订阅者</b>的任务发，避免空转。</p>
     */
    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        emitters.forEach((taskId, list) -> {
            if (!list.isEmpty()) {
                send(taskId, "heartbeat", Map.of("ts", System.currentTimeMillis()));
            }
        });
    }

    /**
     * 当前订阅数（排障与测试用）。
     *
     * @param taskId 任务
     * @return 连接数
     */
    public int connectionCount(Long taskId) {
        List<SseEmitter> list = emitters.get(taskId);
        return list == null ? 0 : list.size();
    }
}
