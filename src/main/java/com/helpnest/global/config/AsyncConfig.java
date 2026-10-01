// @owner BSJ
package com.helpnest.global.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} 활성화 (AI 분류·알림 등 AFTER_COMMIT 리스너, docs/02 §5.1).
 * Executor 는 Boot 자동 구성(applicationTaskExecutor) — 크기는 application.yml spring.task.execution.pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 큐가 차면 거부(기본) 대신 호출 스레드에서 실행. AFTER_COMMIT 리스너가 거부되면
     * 티켓은 커밋됐는데 응답만 500 이 되므로, 느려지더라도 실패하지 않게 한다.
     */
    @Bean
    public ThreadPoolTaskExecutorCustomizer callerRunsOnOverflow() {
        return executor -> executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
