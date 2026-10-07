// @owner PMJ
package com.helpnest.domain.chat.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.helpnest.domain.chat.error.ChatErrorCode;
import com.helpnest.global.error.BusinessException;

/** ChatRoom 전이 규칙: WAITING 에서만 open·convert·cancel, OPEN 에서만 close. */
@DisplayName("ChatRoom — 상태 전이")
class ChatRoomTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");

    private static ChatRoom waiting() {
        return ChatRoom.waiting(7L, NOW);
    }

    @Test
    @DisplayName("요청 직후 WAITING 이고 티켓·상담원이 없다")
    void 대기_시작() {
        ChatRoom room = waiting();

        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.WAITING);
        assertThat(room.getTicketId()).isNull();
        assertThat(room.getAgentId()).isNull();
        assertThat(room.getQueuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("open → 상담원·티켓·연결 시각이 채워지고 close 로 종료된다")
    void 연결_후_종료() {
        ChatRoom room = waiting();
        room.open(3L, 100L, NOW.plusMinutes(1));
        room.close(NOW.plusMinutes(10));

        assertThat(room.getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
        assertThat(room.getAgentId()).isEqualTo(3L);
        assertThat(room.getTicketId()).isEqualTo(100L);
        assertThat(room.getOpenedAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(room.getClosedAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    @DisplayName("convert → CONVERTED + 티켓 연결, cancel → CANCELED + 티켓 없음")
    void 전환과_취소() {
        ChatRoom converted = waiting();
        converted.convert(200L, NOW.plusMinutes(6));
        ChatRoom canceled = waiting();
        canceled.cancel(NOW.plusMinutes(2));

        assertThat(converted.getStatus()).isEqualTo(ChatRoomStatus.CONVERTED);
        assertThat(converted.getTicketId()).isEqualTo(200L);
        assertThat(canceled.getStatus()).isEqualTo(ChatRoomStatus.CANCELED);
        assertThat(canceled.getTicketId()).isNull();
    }

    @Test
    @DisplayName("WAITING 에서 close, OPEN 에서 open·convert·cancel 은 거부된다")
    void 불허_전이() {
        ChatRoom room = waiting();
        assertInvalid(() -> room.close(NOW));

        room.open(3L, 100L, NOW);
        assertInvalid(() -> room.open(4L, 101L, NOW));
        assertInvalid(() -> room.convert(101L, NOW));
        assertInvalid(() -> room.cancel(NOW));

        room.close(NOW);
        assertInvalid(() -> room.close(NOW));
    }

    @Test
    @DisplayName("참여자는 고객 본인과 담당 상담원뿐이다")
    void 참여자_판정() {
        ChatRoom room = waiting();
        assertThat(room.isParticipant(7L)).isTrue();
        assertThat(room.isParticipant(3L)).isFalse();
        assertThat(room.isParticipant(null)).isFalse();

        room.open(3L, 100L, NOW);
        assertThat(room.isParticipant(3L)).isTrue();
        assertThat(room.isParticipant(9L)).isFalse();
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ChatErrorCode.INVALID_STATE);
    }
}
