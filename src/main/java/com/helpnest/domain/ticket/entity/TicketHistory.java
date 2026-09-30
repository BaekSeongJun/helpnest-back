// @owner PMJ
package com.helpnest.domain.ticket.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 티켓의 상태·배정·우선순위·유형 변경 이력. DDL 은 docs/03 §3.2 가 단일 권위다.
 *
 * <h2>추가 전용(append-only)</h2>
 * 변경 메서드가 하나도 없는 것은 빠뜨린 것이 아니다. 이력은 "그때 이런 일이 있었다"는 기록이므로
 * 한 번 쓰면 수정·삭제하지 않는다. 잘못 남긴 이력은 고치는 대신 정정 이력을 한 건 더 쌓는다.
 *
 * <h2>ticket_id 를 연관관계로 두지 않는 이유</h2>
 * Ticket 과 같은 패키지라 {@code @ManyToOne Ticket} 도 문법상 가능하지만 {@code Long} 으로 둔다.
 * 이력을 쌓을 때 필요한 것은 티켓의 식별자뿐이고, 연관관계로 두면 이력 목록을 읽을 때마다
 * 티켓을 함께 적재하거나 지연 로딩 프록시를 신경 써야 한다. 같은 이유로
 * {@link TicketReply} 도 {@code Long} 을 쓰며 티켓 도메인 전체가 이 방식으로 일관된다.
 *
 * <h2>from_value / to_value</h2>
 * VARCHAR(50) 문자열이며 담기는 값의 의미는 {@link HistoryAction} 에 따라 달라진다
 * (상태 전이면 {@link TicketStatus} 이름, 배정이면 member_id 문자열). 각 action 별 규약은
 * {@link HistoryAction} 의 상수 주석에 정리돼 있다.
 */
@Getter
@Entity
@Table(name = "ticket_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;

    /** 대상 티켓의 ticket_id. 연관관계가 아닌 식별자 참조다. */
    @Column(nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HistoryAction action;

    /** 변경 전 값. CREATE 이거나 최초 배정(ASSIGN)이면 NULL 이다. */
    @Column(length = 50)
    private String fromValue;

    /** 변경 후 값. CREATE 면 NULL 이다. */
    @Column(length = 50)
    private String toValue;

    /** 수행자의 member_id. actorType 이 MEMBER 가 아니면 NULL 이다. */
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ActorType actorType;

    /** 담당자에게 남기는 변경 사유. 선택 입력이다. */
    @Column(length = 500)
    private String memo;

    /**
     * 이력이 쌓인 시각. ticket_history 에는 updated_at 컬럼이 없으므로
     * {@code BaseTimeEntity} 를 상속하지 않고 이 필드만 직접 둔다. 상속하면
     * ddl-auto=validate 가 updated_at 컬럼 부재로 기동을 막는다.
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private TicketHistory(Long ticketId, HistoryAction action, String fromValue, String toValue,
            Long actorId, ActorType actorType, String memo) {
        this.ticketId = ticketId;
        this.action = action;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.actorId = actorId;
        this.actorType = actorType;
        this.memo = memo;
    }
}
