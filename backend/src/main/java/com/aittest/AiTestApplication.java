package com.aittest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 测试用例生成与评审平台启动类。
 *
 * <p>{@code @EnableScheduling} 供 {@code GenTaskDispatcher} 的轮询认领使用；
 * {@code @EnableAsync} 供异步任务执行壳（线程池）使用。二者都是异步任务引擎的基础设施，
 * 在此一次性开启，避免后续模块各自重复声明导致线程池冲突。</p>
 *
 * @author 寇豆码
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AiTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiTestApplication.class, args);
    }
}
