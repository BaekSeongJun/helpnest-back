// @owner PMJ
package com.helpnest.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.global.security.JwtProperties;
import com.helpnest.global.security.JwtProvider;

/**
 * STOMP CONNECT 인증 단위 테스트 (docs/02 §6).
 *
 * <p>실제 WebSocket 핸드셰이크를 열지 않는다. 검증 대상은 "CONNECT 프레임의 헤더를 보고
 * 통과시킬지 끊을지, 통과시킬 때 Principal 에 무엇을 넣는지"이고 그것은 인터셉터 한 메서드에
 * 다 들어 있다. 브로커를 띄우면 그 판정이 틀렸을 때 연결 실패와 설정 실수를 구분하기 어려워진다.
 *
 * <p>{@code setLeaveMutable(true)} 가 필요하다 — {@code getMessageHeaders()} 를 부르면
 * 기본적으로 헤더가 불변이 되어 인터셉터의 {@code setUser} 가 반영되지 않고, 테스트가
 * "Principal 이 안 들어갔다"고 거짓 실패한다.
 */
@DisplayName("StompAuthInterceptor — CONNECT 인증과 Principal 설정")
class StompAuthInterceptorTest {

    private static final SecretKey KEY = JwtProvider.secretKey("ws-test-secret-key-32bytes-minimum!!");
    private static final SecretKey OTHER_KEY = JwtProvider.secretKey("ws-other-secret-key-32bytes-minimum!");

    private final ChatRoomRepository chatRooms = mock(ChatRoomRepository.class);
    private final StompAuthInterceptor interceptor = new StompAuthInterceptor(decoder(KEY), chatRooms);

    private static JwtDecoder decoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static JwtProvider provider(SecretKey key) {
        return new JwtProvider(NimbusJwtEncoder.withSecretKey(key).build(),
                new JwtProperties(null, Duration.ofMinutes(30), Duration.ofDays(14), true));
    }

    /** 헤더를 담은 CONNECT 프레임. authorization 이 null 이면 헤더를 아예 넣지 않는다 */
    private static StompHeaderAccessor connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return accessor;
    }

    private static Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("회원 토큰이면 통과하고 Principal 이름이 memberId 다 — /user 큐 라우팅의 키")
    void memberTokenSetsPrincipal() {
        String token = provider(KEY).createAccessToken(7L, "AGENT");
        StompHeaderAccessor accessor = connect("Bearer " + token);

        interceptor.preSend(message(accessor), null);

        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("7");
    }

    @Test
    @DisplayName("Authorization 헤더가 없거나 Bearer 가 아니면 연결을 거부한다")
    void rejectsMissingOrMalformedHeader() {
        assertThatThrownBy(() -> interceptor.preSend(message(connect(null)), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(message(connect("Token abc")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(message(connect("Bearer")), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void rejectsExpiredToken() {
        // 기본 검증기의 시계 오차 허용(60초)보다 충분히 과거에 만료된 토큰
        Instant past = Instant.now().minus(Duration.ofHours(1));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("7").claim(JwtProvider.ROLE_CLAIM, "AGENT")
                .issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(30))).build();
        String expired = NimbusJwtEncoder.withSecretKey(KEY).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> interceptor.preSend(message(connect("Bearer " + expired)), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰은 거부한다 — 서명 검증이 실제로 걸린다")
    void rejectsForgedToken() {
        String forged = provider(OTHER_KEY).createAccessToken(7L, "AGENT");

        assertThatThrownBy(() -> interceptor.preSend(message(connect("Bearer " + forged)), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    /**
     * 비회원은 {@code notification.receiver_id} 가 member FK 라 받을 행 자체가 없다. 연결을
     * 허용하면 아무것도 오지 않는 소켓만 붙어 있게 된다.
     *
     * <p>{@code JwtProvider.memberId} 도 Guest 면 FORBIDDEN 을 던지지만 그것에 기대지 않고
     * 먼저 걸러낸다 — 기대면 BusinessException 이 STOMP 채널로 새어 나가 거부 사유가
     * "인증 실패"로 보이지 않는다.
     */
    @Test
    @DisplayName("Guest 토큰은 거부한다 — 비회원은 개인 알림 대상이 아니다")
    void rejectsGuestToken() {
        String guest = provider(KEY).createGuestToken(42L);

        assertThatThrownBy(() -> interceptor.preSend(message(connect("Bearer " + guest)), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    /** CONNECT 를 통과한 세션의 SUBSCRIBE·SEND 프레임. 실제 런타임처럼 세션 사용자를 헤더에 싣는다 */
    private static Message<byte[]> frame(StompCommand command, String destination, String memberId, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        accessor.setDestination(destination);
        if (memberId != null) {
            accessor.setUser(new StompAuthInterceptor.StompPrincipal(memberId, role));
        }
        return message(accessor);
    }

    @Test
    @DisplayName("CONNECT 후 Principal 에 역할도 담긴다 — 콘솔 토픽 구독 판정에 쓴다")
    void principalCarriesRole() {
        StompHeaderAccessor accessor = connect("Bearer " + provider(KEY).createAccessToken(7L, "LEAD"));
        interceptor.preSend(message(accessor), null);

        assertThat(((StompAuthInterceptor.StompPrincipal) accessor.getUser()).role()).isEqualTo("LEAD");
    }

    @Test
    @DisplayName("채팅방 토픽은 참여자만 구독한다 — 남의 대화를 엿볼 수 없다")
    void chatTopicOnlyForParticipants() {
        when(chatRooms.existsParticipant(10L, 7L)).thenReturn(true);

        assertThatCode(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/10", "7", "CUSTOMER"), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/10", "8", "CUSTOMER"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/chat/abc", "7", "CUSTOMER"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("콘솔 토픽은 직원만, 개인 큐는 누구나, 목록에 없는 목적지는 거부")
    void subscribeAllowList() {
        assertThatCode(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/console/tickets", "3", "AGENT"), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/console/tickets", "7", "CUSTOMER"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatCode(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat-status", "7", "CUSTOMER"), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/anything", "3", "ADMIN"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/user/queue/chat-status", null, null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("SEND 는 /app/** 만 — 브로커 토픽 직접 발행은 메시지 위조라 막는다")
    void sendOnlyToApp() {
        assertThatCode(() -> interceptor.preSend(frame(StompCommand.SEND, "/app/chat/10/send", "7", "CUSTOMER"), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/chat/10", "7", "CUSTOMER"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
