// @owner PMJ
package com.helpnest.domain.chat.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.helpnest.domain.chat.error.ChatErrorCode;
import com.helpnest.global.error.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 1:1 채팅방 (FR-CHT-01~05). DDL 은 docs/03 §3.2 가 단일 권위다.
 *
 * <h2>티켓과 달리 엔티티가 전이를 직접 막는다</h2>
 * 티켓은 역할별 수행자 판정이 필요해 {@code TicketStateMachine} 이 따로 있지만, 채팅방은
 * WAITING 에서 갈라지는 4갈래 + OPEN→CLOSED 뿐이고 수행자 판정은 서비스가 참여자 확인으로
 * 끝낸다. 판정 클래스를 따로 두면 5줄짜리 규칙이 두 파일로 갈라지므로 메서드 안에서 검증한다.
 *
 * <h2>회원 참조</h2>
 * {@code Ticket} 과 같은 이유로 ticket·member 를 연관관계가 아닌 {@code Long} 식별자로 둔다
 * (docs/02 §5). updated_at 컬럼이 없어 {@code BaseTimeEntity} 를 상속하지 않는다.
 */
@Getter
@Entity
@Table(name = "chat_room")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long id;

    /** OPEN·CONVERTED 가 되면서 생기는 티켓. WAITING·CANCELED 동안 NULL 이다. */
    private Long ticketId;

    @Column(nullable = false)
    private Long customerId;

    /** 배정된 상담원. OPEN 전이면 NULL 이다. */
    private Long agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChatRoomStatus status;

    /** 대기 순번의 기준(docs/03 §4.7). 5분 초과 판정도 이 값으로 한다. */
    @Column(nullable = false)
    private OffsetDateTime queuedAt;

    private OffsetDateTime openedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime closedAt;

    private ChatRoom(Long customerId, OffsetDateTime queuedAt) {
        this.customerId = customerId;
        this.queuedAt = queuedAt;
        this.status = ChatRoomStatus.WAITING;
    }

    /** 채팅 요청. 항상 WAITING 으로 시작하고, 배정 가능하면 서비스가 곧바로 {@link #open} 한다. */
    public static ChatRoom waiting(Long customerId, OffsetDateTime queuedAt) {
        return new ChatRoom(customerId, queuedAt);
    }

    /** 상담원 연결. CHAT 티켓은 호출 전에 만들어져 있어야 한다(FR-CHT-01). */
    public void open(Long agentId, Long ticketId, OffsetDateTime at) {
        require(ChatRoomStatus.WAITING);
        this.agentId = agentId;
        this.ticketId = ticketId;
        this.openedAt = at;
        this.status = ChatRoomStatus.OPEN;
    }

    /** 상담원의 상담 종료(FR-CHT-03). 티켓 RESOLVED 전이는 서비스 책임이다. */
    public void close(OffsetDateTime at) {
        require(ChatRoomStatus.OPEN);
        this.closedAt = at;
        this.status = ChatRoomStatus.CLOSED;
    }

    /** 대기 중 "문의로 남기기"(FR-CHT-05). 5분 경과 검증은 시각 정책이라 서비스가 한다. */
    public void convert(Long ticketId, OffsetDateTime at) {
        require(ChatRoomStatus.WAITING);
        this.ticketId = ticketId;
        this.closedAt = at;
        this.status = ChatRoomStatus.CONVERTED;
    }

    /** 대기 중 나가기(FR-CHT-05). 티켓을 만들지 않는다. */
    public void cancel(OffsetDateTime at) {
        require(ChatRoomStatus.WAITING);
        this.closedAt = at;
        this.status = ChatRoomStatus.CANCELED;
    }

    /** 이 회원이 대화 참여자(고객 본인 또는 담당 상담원)인지. */
    public boolean isParticipant(Long memberId) {
        return memberId != null && (memberId.equals(customerId) || memberId.equals(agentId));
    }

    private void require(ChatRoomStatus expected) {
        if (status != expected) {
            throw new BusinessException(ChatErrorCode.INVALID_STATE);
        }
    }
}
