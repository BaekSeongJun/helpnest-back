// @owner PMJ
package com.helpnest.global.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /** 클라이언트 → 서버 인바운드 채널에만 인증 인터셉터를 건다(CONNECT 가 여기로 들어온다) */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }
}
