// @owner PMJ
package com.helpnest.domain.ticket.entity;

import java.time.OffsetDateTime;

import com.helpnest.global.common.BaseTimeEntity;

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
 * 문의 티켓. 티켓 도메인의 애그리거트 루트이며 DDL 은 docs/03 §3.2 가 단일 권위다.
 *
 * <h2>회원 참조를 연관관계로 두지 않는 이유</h2>
 * customer_id·agent_id 는 DB 상 member(member_id) FK 지만 {@code @ManyToOne Member} 로
 * 매핑하지 않고 {@code Long} 식별자로만 둔다. 다른 도메인의 Entity 를 import 하지 않는 것이
 * 아키텍처 규칙이기 때문이다(docs/02 §5, docs/10 §3.3). 회원의 이름·이메일이 필요하면
 * 백성준의 {@code MemberQueryPort} 로 조회한다.
 *
 * <h2>상태 변경 규칙</h2>
 * {@code @Setter} 는 없고 의미 있는 변경 메서드만 공개한다. 다만 이 메서드들은
 * <b>전이 가능 여부를 검증하지 않는다</b>. PRD 5장 전이표 판정은
 * {@link com.helpnest.domain.ticket.service.TicketStateMachine} 의 책임이고 그 호출은
 * S1 TicketService 가 이 메서드보다 먼저 수행한다. 엔티티가 스스로 판정하면 판정 로직이
 * 전이표와 엔티티 두 곳으로 갈라진다.
 *
 * <h2>비회원 티켓</h2>
 * customer_id 가 NULL 이면 비회원 문의이며 guest_email 로 식별한다
 * (DDL 의 chk_ticket_customer 제약이 둘 중 하나는 반드시 있도록 보장한다).
 */
@Getter
@Entity
@Table(name = "ticket")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id")
    private Long id;

    /** 고객에게 보여 주는 티켓번호. 형식 HN-20261002-000123 (FR-INQ-03). */
    @Column(nullable = false, unique = true, length = 20)
    private String ticketNo;

    /** 회원 고객의 member_id. 비회원이면 NULL 이다. 연관관계가 아닌 식별자 참조다. */
    private Long customerId;

    /** 비회원 이름. 회원 티켓이면 NULL 이다. */
    @Column(length = 50)
    private String guestName;

    /** 비회원 이메일. 비회원 티켓 조회의 식별자이며 로그에 출력하면 안 된다(docs/10 §3.3). */
    @Column(length = 100)
    private String guestEmail;

    /** 비회원 조회 비밀번호의 BCrypt 해시. 원문은 저장하지 않으며 로그에 출력하면 안 된다. */
    @Column(length = 100)
    private String guestPasswordHash;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketPriority priority;

    /** LLM 감정 판정 결과. NULL 은 중립이 아니라 아직 분류하지 않았음을 뜻한다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Sentiment sentiment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    /** 담당 상담원의 member_id. 배정 전이면 NULL 이다. */
    private Long agentId;

    /** 첫 응답 기한(PRD 6.1). 우선순위가 바뀌면 재계산해야 한다. */
    @Column(nullable = false)
    private OffsetDateTime firstResponseDueAt;

    /** 상담원의 첫 답변 시각. NULL 이면 아직 응답이 없다는 뜻이며 SLA 감시 대상이다. */
    private OffsetDateTime firstRespondedAt;

    /** 임박 알림을 이미 보냈는지. 스케줄러의 중복 발송을 막는 플래그다. */
    @Column(nullable = false)
    private boolean slaWarned;

    /** 기한을 넘겼는지. 한 번 true 가 되면 되돌리지 않는다. */
    @Column(nullable = false)
    private boolean slaBreached;

    private OffsetDateTime assignedAt;

    private OffsetDateTime resolvedAt;

    private OffsetDateTime closedAt;

    /**
     * 접수 시점에 확정되는 값만 받는다. priority·sentiment 는 AI 분류가 정하고(docs/05)
     * status 는 항상 RECEIVED 로 시작하므로 빌더에 노출하지 않는다.
     *
     * <p><b>category 는 받는다.</b> 접수 폼에서 고객이 고른 유형(FR-INQ-01)을 담을 컬럼이
     * category 뿐이기 때문이다 — DDL 에 category_hint 같은 별도 컬럼이 없고(docs/03 §3.2),
     * {@code TicketQueryPort.getTicketSummary} 가 LLM 프롬프트용으로 돌려주는 categoryHint 가
     * 바로 이 값이다({@code TicketSummary} 주석의 "분류 전 기본값이면 ETC"). 고객 선택을
     * 버리면 docs/05 §3.2 의 프롬프트 입력 한 칸이 영구히 비게 된다. AI 분류가 끝나면
     * {@link #applyClassification} 이 이 값을 자기 판정으로 덮어쓴다.
     *
     * @param category           고객이 고른 유형. 고르지 않았으면 null 을 넘기고 ETC 가 된다.
     * @param firstResponseDueAt 기본 우선순위(NORMAL) 기준으로 계산한 기한.
     *                           계산은 SlaPolicy.calculateDueAt 이 수행한다.
     */
    @Builder
    private Ticket(String ticketNo, Long customerId, String guestName, String guestEmail,
            String guestPasswordHash, String title, String content, TicketChannel channel,
            TicketCategory category, OffsetDateTime firstResponseDueAt) {
        this.ticketNo = ticketNo;
        this.customerId = customerId;
        this.guestName = guestName;
        this.guestEmail = guestEmail;
        this.guestPasswordHash = guestPasswordHash;
        this.title = title;
        this.content = content;
        this.channel = channel;
        this.firstResponseDueAt = firstResponseDueAt;
        this.category = category != null ? category : TicketCategory.ETC;
        this.priority = TicketPriority.NORMAL;
        this.status = TicketStatus.RECEIVED;
        this.slaWarned = false;
        this.slaBreached = false;
    }

    /** 회원 티켓인지. false 면 비회원 티켓이며 {@link #getGuestEmail()} 로 식별한다. */
    public boolean isMemberTicket() {
        return customerId != null;
    }

    /**
     * 담당 상담원을 배정하고 상태를 ASSIGNED 로 바꾼다. 최초 배정과 재배정을 함께 처리한다.
     * 전이표에 RECEIVED·ASSIGNED·IN_PROGRESS 에서 ASSIGNED 로 가는 전이가 모두 있으므로
     * 배정 경로는 세 상태 전부에서 합법이다(PRD 5장). 재배정이면 assignedAt 도 새로 갱신한다.
     *
     * @param agentId 새 담당 상담원의 member_id
     * @param at      배정 시각. 엔티티가 now() 를 직접 부르지 않는 이유는 테스트에서
     *                시각을 고정할 수 있어야 하기 때문이다.
     */
    public void assignTo(Long agentId, OffsetDateTime at) {
        this.agentId = agentId;
        this.assignedAt = at;
        this.status = TicketStatus.ASSIGNED;
    }

    /**
     * 상태를 바꾸고 RESOLVED·CLOSED 일 때 해당 시각 컬럼을 함께 채운다.
     * <b>전이 가능 여부는 검증하지 않는다</b> — 호출 전에 TicketStateMachine 으로 판정해야 한다
     * (클래스 주석의 상태 변경 규칙 참고).
     *
     * @param to 새 상태
     * @param at 전이 시각
     */
    public void changeStatusTo(TicketStatus to, OffsetDateTime at) {
        this.status = to;
        if (to == TicketStatus.RESOLVED) {
            this.resolvedAt = at;
        } else if (to == TicketStatus.CLOSED) {
            this.closedAt = at;
        }
    }

    /**
     * AI 분류 결과를 반영한다(docs/05).
     *
     * <p>우선순위가 바뀌면 첫 응답 기한도 달라지지만 이 메서드는 기한을 건드리지 않는다.
     * 기한 계산에는 SlaPolicy 조회가 필요해 엔티티 혼자 할 수 없기 때문이다. 호출자는
     * 우선순위 변경 시 {@link #updateFirstResponseDueAt(OffsetDateTime)} 를 반드시 함께
     * 호출해야 한다(PRD 6.1 "AI 분류로 우선순위가 바뀌면 재계산").
     *
     * <p>TODO(PMJ): S1 TicketService 에서 이 두 호출을 한 트랜잭션으로 묶고, 우선순위 변경
     * 이력(HistoryAction.PRIORITY_CHANGE)도 같은 자리에서 남긴다.
     */
    public void applyClassification(TicketCategory category, TicketPriority priority, Sentiment sentiment) {
        this.category = category;
        this.priority = priority;
        this.sentiment = sentiment;
    }

    /** 우선순위 변경에 따라 재계산한 첫 응답 기한을 반영한다(PRD 6.1). */
    public void updateFirstResponseDueAt(OffsetDateTime dueAt) {
        this.firstResponseDueAt = dueAt;
    }

    /**
     * 첫 응답 시각을 기록한다. 이미 기록돼 있으면 무시한다. 첫 응답은 한 번만 성립하므로
     * 상담원이 답변을 여러 번 달아도 최초 시각이 덮여 SLA 판정이 흐트러지면 안 된다.
     *
     * @param at 첫 답변 시각
     */
    public void markFirstResponded(OffsetDateTime at) {
        if (this.firstRespondedAt == null) {
            this.firstRespondedAt = at;
        }
    }

    /** 임박 알림 발송을 표시한다. 스케줄러가 같은 티켓에 중복 알림을 보내지 않게 하는 용도다. */
    public void markSlaWarned() {
        this.slaWarned = true;
    }

    /** SLA 위반을 표시한다. 되돌리는 메서드는 의도적으로 두지 않는다(위반 사실은 사라지지 않는다). */
    public void markSlaBreached() {
        this.slaBreached = true;
    }
}
