// @owner BSJ
package com.helpnest.domain.template.entity;

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
 * 상담원 답변 템플릿 (FR-TPL-01, 02). 본문의 {고객명}·{티켓번호} 는 삽입 시 프론트가 치환한다.
 * 유형은 티켓 유형과 같은 값 집합이라 {@link TicketCategory} 를 그대로 쓴다 (03 §2.1).
 */
@Getter
@Entity
@Table(name = "template")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Template extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketCategory category;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** false 면 상담원 TemplatePicker 에 나오지 않는다 (관리 화면에는 보임) */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** 작성자 member_id */
    @Column(nullable = false, updatable = false)
    private Long createdBy;

    @Builder
    private Template(TicketCategory category, String title, String content, boolean active, Long createdBy) {
        this.category = category;
        this.title = title;
        this.content = content;
        this.active = active;
        this.createdBy = createdBy;
    }

    public void update(TicketCategory category, String title, String content, boolean active) {
        this.category = category;
        this.title = title;
        this.content = content;
        this.active = active;
    }
}
