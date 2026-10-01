// @owner BSJ
package com.helpnest.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** CR #22: @Async 공용 풀이 설정대로 뜨고, 넘치면 거부 대신 호출 스레드에서 실행 */
@SpringBootTest
class AsyncConfigTest {

    @Autowired
    ThreadPoolTaskExecutor applicationTaskExecutor;

    @Test
    @DisplayName("applicationTaskExecutor: async- 접두어, 2/4/100, CallerRunsPolicy")
    void executorConfigured() {
        ThreadPoolExecutor pool = applicationTaskExecutor.getThreadPoolExecutor();

        assertThat(applicationTaskExecutor.getThreadNamePrefix()).isEqualTo("async-");
        assertThat(pool.getCorePoolSize()).isEqualTo(2);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(applicationTaskExecutor.getQueueCapacity()).isEqualTo(100);
        assertThat(pool.getRejectedExecutionHandler()).isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
}
