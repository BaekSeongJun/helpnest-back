// @owner BSJ
package com.helpnest.domain.faq.entity;

import com.helpnest.domain.ticket.entity.TicketCategory;
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
 * FAQ (FR-FAQ-01, 02). 유형은 티켓 유형과 같은 값 집합이라 {@link TicketCategory} 를 그대로 쓴다
 * (Entity 가 아닌 값 enum — 03 §2.1 이 단일 기준).
 */
@Getter
@Entity
@Table(name = "faq")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Faq extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "faq_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketCategory category;

    @Column(nullable = false, length = 300)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    /** 증가는 FaqRepository.incrementViewCount (원자적 UPDATE) 로만 */
    @Column(nullable = false)
    private int viewCount;

    /** 작성자 member_id */
    @Column(nullable = false, updatable = false)
    private Long createdBy;

    @Builder
    private Faq(TicketCategory category, String question, String answer, boolean published, Long createdBy) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.published = published;
        this.createdBy = createdBy;
    }

    public void update(TicketCategory category, String question, String answer, boolean published) {
        this.category = category;
        this.question = question;
        this.answer = answer;
        this.published = published;
    }
}
