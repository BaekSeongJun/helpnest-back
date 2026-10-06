// @owner PMJ
package com.helpnest.global.websocket;

import java.security.Principal;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;

    /**
     * CONNECT 외의 프레임(SUBSCRIBE·SEND·DISCONNECT)은 그대로 통과시킨다. 세션 사용자는
     * CONNECT 에서 한 번 확정되고 이후 프레임은 같은 세션을 쓰므로 매 프레임 검증할 필요가 없다.
     *
     * <p>토큰이 CONNECT 이후 만료되면 연결은 유지된다. 알림은 읽기 전용 수신이고 민감한 조작은
     * 전부 REST 를 거치므로 허용 가능한 범위다.
     *
     * <p>ponytail: 세션 수명을 토큰 수명에 맞춰야 하면 하트비트에 재검증을 붙이거나 만료 시각에
     * 맞춰 세션을 끊는다. 지금은 채팅(S3)이 붙기 전이라 수신 전용이다.
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || StompCommand.CONNECT != accessor.getCommand()) {
            return message;
        }
        accessor.setUser(authenticate(accessor, message));
        return message;
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
        return new StompPrincipal(String.valueOf(memberId));
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
     * {@code Authentication} 을 쓰지 않는 이유는 지금 필요한 것이 이름 하나뿐이고,
     * {@code @MessageMapping} 엔드포인트가 없어 권한 판정 대상이 없기 때문이다(채팅은 S3).
     */
    record StompPrincipal(String name) implements Principal {

        @Override
        public String getName() {
            return name;
        }
    }
}
