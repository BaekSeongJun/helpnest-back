// @owner PMJ
package com.helpnest.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 브로커 heartbeat 와 {@code @Scheduled} 스케줄러 분리 (WebSocketConfig).
 *
 * <p>둘 다 설정이 빠져도 기동·기존 테스트는 전부 통과한다 — heartbeat 는 운영 CloudFront 에서 10분 뒤에야,
 * 스케줄러 공유는 느린 작업이 몰릴 때에야 드러난다. 그래서 설정 값을 직접 고정한다.
 *
 * <p>{@code @SpringBootTest + @AutoConfigureMockMvc} 는 다른 API 테스트와 컨텍스트를 공유하려는 것이다.
 * RANDOM_PORT 로 실제 핸드셰이크를 열면 컨텍스트가 하나 더 떠 PG 연결 상한에 걸린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("WebSocket 브로커 heartbeat·스케줄러 분리")
class WebSocketConfigTest {

    @Autowired
    SimpleBrokerMessageHandler simpleBrokerMessageHandler;

    @Autowired
    @Qualifier("taskScheduler")
    ThreadPoolTaskScheduler taskScheduler;

    @Test
    @DisplayName("브로커가 10초 heartbeat 를 협상하고 보낼 스케줄러를 가진다")
    void brokerHeartbeat() {
        // 스케줄러가 없으면 Spring 이 heartbeat 값을 무시하고 CONNECTED 에 0,0 을 돌려준다
        assertThat(simpleBrokerMessageHandler.getHeartbeatValue()).containsExactly(10_000, 10_000);
        assertThat(simpleBrokerMessageHandler.getTaskScheduler()).isNotNull();
    }

    @Test
    @DisplayName("@Scheduled 작업은 브로커 풀이 아니라 taskScheduler 에 등록된다")
    void scheduledJobsUseOwnScheduler() {
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("scheduling-");
        // 주기 작업은 컨텍스트 기동 시 스케줄러 큐에 올라간다. 비어 있으면 브로커 풀이 가져간 것이다
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getQueue()).isNotEmpty();
        assertThat(taskScheduler).isNotSameAs(simpleBrokerMessageHandler.getTaskScheduler());
    }
}
