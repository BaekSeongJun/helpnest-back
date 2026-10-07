// @owner PMJ
package com.helpnest.global.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * STOMP over WebSocket 설정. 값은 docs/02 §6 이 단일 권위다.
 *
 * <p>소유자는 박민재다 — 패키지가 {@code global} 이지만 docs/02 §3 이 WebSocket 을 박민재로
 * 명시한다. {@code SecurityConfig}(백성준) 의 {@code permitAll} 에 {@code /ws/**} 와 CORS 가
 * 이미 반영돼 있어 그 파일은 건드리지 않는다 — <b>STOMP 인증은 HTTP 필터가 아니라
 * {@link StompAuthInterceptor} 의 책임</b>이다. 핸드셰이크 자체는 누구나 할 수 있고 CONNECT
 * 프레임에서 걸러진다.
 *
 * <h2>setAllowedOrigins 를 반드시 지정한다</h2>
 * REST 는 Next.js rewrites 로 같은 출처가 되지만 {@code /ws} 는 프록시를 거치지 않고 백엔드에
 * 직접 연결된다(docs/02 §2.1). 그래서 교차 출처가 되고, 비워 두면 Spring 이 같은 출처만
 * 허용해 브라우저에서 연결이 거부된다. 값은 하드코딩하지 않고 {@code app.front-origin} 을
 * 주입받는다 — S4 배포에서 값만 바뀐다.
 *
 * <p>ponytail: SimpleBroker 는 구독 정보를 <b>이 인스턴스의 메모리</b>에 들고 있다. 단일
 * 인스턴스 전제이며, 서버를 2대 이상으로 늘리면 A 에 붙은 사용자가 B 에서 발행한 알림을 받지
 * 못한다. 다중화가 필요해지면 외부 브로커(RabbitMQ STOMP relay)로 바꾼다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor stompAuthInterceptor;

    @Value("${app.front-origin}")
    private String frontOrigin;

    /** heartbeat 송신용. 이 클래스가 브로커 설정에 쓰므로 바로 주입하면 순환 의존이 된다 — @Lazy 필수 */
    private TaskScheduler messageBrokerTaskScheduler;

    @Autowired
    public void setMessageBrokerTaskScheduler(
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    /**
     * {@code @Scheduled} 전용 스케줄러를 되살린다.
     *
     * <p>{@code @EnableWebSocketMessageBroker} 가 {@code messageBrokerTaskScheduler} 를 등록하면 Boot 의
     * 기본 {@code taskScheduler} 는 {@code @ConditionalOnMissingBean(TaskScheduler)} 라 만들어지지 않고,
     * SLA·자동 종료·채팅 대기열·메일 재시도·고아 첨부 정리가 전부 브로커 풀(CPU 수만큼)에서 돈다
     * (10/7 실행 중 서버 스레드 덤프에 {@code scheduling-*} 없이 {@code MessageBroker-*} 만 있음을 확인).
     * 그 풀은 heartbeat 도 보내므로, SES 호출처럼 느린 작업이 몰리면 heartbeat 가 밀려 연결이 끊긴다.
     * application-ws.yml 이 Executor 에 대해 되돌린 것과 같은 부수효과다.
     *
     * <p>빈 이름이 {@code taskScheduler} 여야 한다 — TaskScheduler 가 둘이 되면
     * {@code @Scheduled} 처리기가 이 이름으로 고른다. Boot 가 남겨 둔 builder 를 써서
     * {@code spring.task.scheduling.*} 설정과 {@code scheduling-} 스레드 이름을 그대로 따른다.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /** SockJS 를 쓰지 않는다(docs/02 §6) — 대상 브라우저가 모두 WebSocket 을 지원한다 */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(frontOrigin);
    }

    /**
     * {@code /topic} 은 다수 구독자(콘솔 목록 갱신), {@code /queue} 는 개인 수신이다.
     *
     * <p>{@code userDestinationPrefix} 는 기본값 {@code /user} 를 그대로 쓴다. 클라이언트가
     * {@code /user/queue/notifications} 를 구독하면 Spring 이 세션별 실제 목적지로 바꿔 주며,
     * 서버는 {@code SimpMessagingTemplate#convertAndSendToUser} 로 {@code /queue/notifications}
     * 만 지정한다(docs/04 §11).
     *
     * <p>heartbeat 를 10초로 켠다. 운영의 CloudFront 는 오리진 → 클라이언트 바이트가 10분 없으면
     * WebSocket 을 끊는다(AWS 문서 "Use WebSockets with CloudFront distributions"). 스케줄러를 주지
     * 않으면 서버가 0,0 을 돌려줘 stompjs 기본값(10000/10000)도 꺼지고, 알림이 뜸한 상담원 콘솔은
     * 10분마다 끊겨 재연결(5초) 사이의 푸시를 잃는다.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    /** 클라이언트 → 서버 인바운드 채널에만 인증 인터셉터를 건다(CONNECT 가 여기로 들어온다) */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
