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
 * 티켓의 답변과 내부 메모. DDL 은 docs/03 §3.2 가 단일 권위다.
 *
 * <h2>고객 노출 여부</h2>
 * {@link #isInternal()} 이 true 면 상담원끼리만 보는 내부 메모이므로 고객 화면 조회에서
 * 반드시 걸러야 한다. 이 플래그 하나가 유출 여부를 가르므로, 필터링을 화면단이 아니라
 * 조회 쿼리에서 처리하는 것이 안전하다(S1 에서 조회 메서드를 추가할 때 함께 적용).
 *
 * <h2>추가 전용(append-only)</h2>
 * {@link TicketHistory} 와 같은 이유로 변경 메서드를 두지 않는다. 답변 수정은 범위 외이며
 * (PRD 에 수정 요구사항이 없다) 필요해지면 그때 수정 이력 설계를 함께 도입한다.
 *
 * <h2>ai_draft_id</h2>
 * 신수진의 AI_DRAFT 테이블을 참조하지만 DDL 에 FK 가 없다(docs/03 §3.2 주석). 도메인을 넘는
 * 참조를 값으로만 두어 마이그레이션 적용 순서에 서로 묶이지 않게 한 설계이므로, 여기서도
 * 식별자 값만 보관하고 초안 내용이 필요하면 신수진의 포트로 조회한다(docs/02 §5).
 */
@Getter
@Entity
@Table(name = "ticket_reply")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketReply {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reply_id")
    private Long id;

    /** 대상 티켓의 ticket_id. 연관관계가 아닌 식별자 참조다. */
    @Column(nullable = false)
    private Long ticketId;

    /** 작성자의 member_id. writerType 이 GUEST·SYSTEM 이면 NULL 이다. */
    private Long writerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WriterType writerType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 내부 메모 여부. true 면 고객에게 보여 주지 않는다. */
    @Column(name = "is_internal", nullable = false)
    private boolean isInternal;

    /** 이 답변을 작성할 때 사용한 AI 초안의 id(신수진 AI_DRAFT). 직접 작성이면 NULL 이다. */
    private Long aiDraftId;

    /**
     * 작성 시각. ticket_reply 에는 updated_at 컬럼이 없으므로 {@code BaseTimeEntity} 를
     * 상속하지 않고 이 필드만 직접 둔다.
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private TicketReply(Long ticketId, Long writerId, WriterType writerType, String content,
            boolean isInternal, Long aiDraftId) {
        this.ticketId = ticketId;
        this.writerId = writerId;
        this.writerType = writerType;
        this.content = content;
        this.isInternal = isInternal;
        this.aiDraftId = aiDraftId;
    }
}
