// @owner PMJ
package com.helpnest.global.websocket;

import java.security.Principal;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.global.security.JwtProvider;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP {@code CONNECT} 프레임의 JWT 를 검증하고 세션 사용자를 확정한다 (docs/02 §6).
 *
 * <h2>왜 HTTP 필터가 아니라 인터셉터인가</h2>
 * 브라우저 WebSocket API 는 핸드셰이크 요청에 임의 헤더를 넣을 수 없어 {@code Authorization}
 * 을 HTTP 단계에서 받을 방법이 없다. 그래서 {@code SecurityConfig} 는 {@code /ws/**} 를
 * {@code permitAll} 로 열어 두고(백성준 파일, 수정하지 않는다), 인증은 STOMP 프로토콜의 첫
 * 프레임인 CONNECT 의 네이티브 헤더로 한다 — 프론트의 {@code @stomp/stompjs} 는 거기에
 * {@code connectHeaders} 를 실을 수 있다.
 *
 * <h2>Principal 이 이 클래스의 존재 이유다</h2>
 * {@code /user/queue/notifications} 라우팅은 STOMP 세션의 {@link Principal#getName()} 과
 * {@code convertAndSendToUser(user, ...)} 의 {@code user} 를 문자열로 맞춰 큐를 찾는다.
 * 그래서 이름에 <b>member_id 문자열</b>을 넣는다. 이 값을 빠뜨리거나 다른 값을 넣으면
 * 예외도 나지 않고 <b>알림이 조용히 아무에게도 가지 않는다</b> — 가장 찾기 어려운 실패 모드라
 * 테스트도 "연결됐는가"가 아니라 "Principal 에 memberId 가 들어갔는가"를 본다.
 *
 * <h2>Guest 토큰은 거부한다</h2>
 * 비회원은 개인 알림 대상이 아니다 — {@code notification.receiver_id} 가 member 를 FK 로
 * 참조하므로 받을 행 자체가 만들어지지 않는다. 연결을 허용하면 아무것도 오지 않는 소켓을
 * 붙여 두는 셈이라 CONNECT 에서 끊는다.
 *
 * <h2>SUBSCRIBE·SEND 도 막는다 (S3 채팅)</h2>
 * SimpleBroker 는 목적지만 맞으면 누구에게나 구독을 열어 주고, 클라이언트가 {@code /topic/...} 으로
 * 직접 SEND 하면 서버 코드를 거치지 않고 그대로 구독자에게 뿌린다. 막지 않으면
 * <ul>
 *   <li>로그인한 아무나 {@code /topic/chat/{남의 방}} 을 구독해 대화를 엿보고,</li>
 *   <li>{@code /topic/chat/{방}} 으로 직접 SEND 해 저장·검증 없이 가짜 메시지를 끼워 넣는다.</li>
 * </ul>
 * 그래서 SEND 는 {@code /app/**}(서버 핸들러 경유)만, SUBSCRIBE 는 아래 허용 목록만 통과시킨다.
 * 목록에 없는 목적지는 거부한다 — 새 토픽을 열 때 여기에 규칙을 추가해야 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";
    private static final String CHAT_TOPIC = "/topic/chat/";
    private static final Set<String> STAFF_ROLES = Set.of("AGENT", "LEAD", "ADMIN");

    private final JwtDecoder jwtDecoder;
    private final ChatRoomRepository chatRoomRepository;

    /**
     * CONNECT 에서 토큰을 검증해 세션 사용자를 확정하고, SUBSCRIBE·SEND 는 그 사용자로 목적지를
     * 판정한다(클래스 주석). 토큰은 CONNECT 에서 한 번만 본다 — 이후 프레임은 같은 세션이다.
     *
     * <p>ponytail: 토큰이 CONNECT 이후 만료돼도 세션은 유지되고 채팅 송신도 계속된다(최대 Access
     * 토큰 수명 30분 + 재연결 전까지). 세션 수명을 토큰에 맞춰야 하면 만료 시각에 세션을 끊는다.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        switch (accessor.getCommand()) {
            case CONNECT -> accessor.setUser(authenticate(accessor, message));
            case SUBSCRIBE -> authorizeSubscribe(accessor, message);
            case SEND -> authorizeSend(accessor, message);
            default -> { }
        }
        return message;
    }

    /**
     * 구독 허용 목록. {@code /user/queue/**} 는 Spring 이 세션 사용자 전용 목적지로 바꾸므로 남의 큐를
     * 구독할 수 없다. 채팅방은 참여자만, 콘솔 토픽은 직원만 받는다.
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        StompPrincipal user = principal(accessor, message);
        if (destination == null) {
            throw reject(message, "구독 목적지 없음");
        }
        if (destination.startsWith("/user/queue/")) {
            return;
        }
        if (destination.startsWith("/topic/console/") && STAFF_ROLES.contains(user.role())) {
            return;
        }
        if (destination.startsWith(CHAT_TOPIC) && isChatParticipant(destination, user)) {
            return;
        }
        throw reject(message, "구독 거부 destination=%s memberId=%s".formatted(destination, user.name()));
    }

    private boolean isChatParticipant(String destination, StompPrincipal user) {
        try {
            Long roomId = Long.valueOf(destination.substring(CHAT_TOPIC.length()));
            return chatRoomRepository.existsParticipant(roomId, Long.valueOf(user.name()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 클라이언트는 서버 핸들러({@code /app/**})로만 보낸다. 브로커 목적지 직접 발행은 위조다 */
    private void authorizeSend(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            throw reject(message, "SEND 거부 destination=" + destination);
        }
    }

    /** CONNECT 를 통과한 세션이면 항상 있다. 없으면 인증 없이 들어온 프레임이다 */
    private StompPrincipal principal(StompHeaderAccessor accessor, Message<?> message) {
        if (accessor.getUser() instanceof StompPrincipal user) {
            return user;
        }
        throw reject(message, "인증되지 않은 세션");
    }

    private Principal authenticate(StompHeaderAccessor accessor, Message<?> message) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw reject(message, "Authorization 헤더 없음");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER.length()));
        } catch (JwtException e) {
            // 토큰 원문은 로그에 남기지 않는다 — 자격증명이다(docs/10 §3.3)
            throw reject(message, "토큰 검증 실패: " + e.getMessage());
        }

        if (JwtProvider.GUEST_ROLE.equals(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM))) {
            throw reject(message, "비회원 토큰은 개인 알림 대상이 아님");
        }

        Long memberId = JwtProvider.memberId(jwt);
        log.debug("[ws] CONNECT 허용 memberId={}", memberId);
        return new StompPrincipal(String.valueOf(memberId), jwt.getClaimAsString(JwtProvider.ROLE_CLAIM));
    }

    /**
     * 예외를 던져 CONNECT 를 거부한다. Spring 이 ERROR 프레임을 보내고 세션을 닫으므로
     * 클라이언트는 연결 실패를 알 수 있다 — 조용히 통과시키면 Principal 없는 세션이 생겨
     * 구독은 되지만 알림만 영원히 오지 않는다.
     */
    private MessageDeliveryException reject(Message<?> message, String reason) {
        log.warn("[ws] CONNECT 거부 — {}", reason);
        return new MessageDeliveryException(message, "STOMP 인증 실패");
    }

    /**
     * {@code /user/...} 라우팅이 쓰는 이름만 담는 최소 구현. Spring Security 의
     * {@code Authentication} 을 쓰지 않는 이유는 필요한 것이 이름과 역할 두 개뿐이기 때문이다.
     * 역할은 구독 판정(콘솔 토픽은 직원만)에 쓴다.
     */
    record StompPrincipal(String name, String role) implements Principal {

        @Override
        public String getName() {
            return name;
        }
    }
}
