package com.aittest.config;

import com.aittest.logging.TraceFilter;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步线程池配置（任务引擎的执行壳，见 §5.3）。
 *
 * <p>三条设计要点：</p>
 * <ol>
 *   <li><b>有界队列 + CallerRunsPolicy</b>：宁可拖慢调度线程，也不丢任务、不 OOM。
 *       无界队列在 LLM 长耗时场景下会堆积任务直到内存爆掉；</li>
 *   <li><b>MDC 透传</b>：{@link TraceFilter} 把 traceId 放在父线程的 MDC 里，
 *       线程池默认不继承 → 异步任务的日志会丢 traceId。用 TaskDecorator 复制过去；</li>
 *   <li><b>线程名前缀</b> {@code ai-task-}：线程 dump 时能一眼区分业务线程与 Tomcat 线程。</li>
 * </ol>
 *
 * <p>注意：本线程池只负责"执行壳"，真正的任务认领仍是 {@code @Scheduled} 轮询 +
 * {@code FOR UPDATE SKIP LOCKED}（见 T06），两者别混为一谈。</p>
 *
 * @author 寇豆码
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /** 线程池 Bean 名称（{@code @Async("aiTaskExecutor")} 引用它）。 */
    public static final String AI_TASK_EXECUTOR = "aiTaskExecutor";

    /** 核心线程数。 */
    private static final int CORE_POOL_SIZE = 4;

    /** 最大线程数。 */
    private static final int MAX_POOL_SIZE = 8;

    /** 队列容量（有界，满了走 CallerRuns）。 */
    private static final int QUEUE_CAPACITY = 200;

    /** 空闲线程存活秒数。 */
    private static final int KEEP_ALIVE_SECONDS = 60;

    /**
     * 任务执行线程池。
     *
     * @return 线程池
     */
    @Bean(name = AI_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("ai-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // 透传 traceId，否则异步任务日志无法与请求日志串联
        executor.setTaskDecorator(runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return aiTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步任务未捕获异常 method={} params={}",
                method == null ? "" : method.getName(), params, ex);
    }
}
