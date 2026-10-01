// @owner SSJ
package com.helpnest.domain.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI-2 답변 초안 (docs/03 §3.3). updated_at 이 없어 BaseTimeEntity 미상속 */
@Getter
@Entity
@Table(name = "ai_draft")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "draft_id")
    private Long id;

    @Column(nullable = false)
    private Long ticketId;

    @Column(nullable = false)
    private Long requestedBy;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // [{"type":"FAQ","id":3},{"type":"REPLY","id":41}]
    @JdbcTypeCode(SqlTypes.JSON)
    private String referenceRefs;

    @Column(length = 100)
    private String model;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private AiDraft(Long ticketId, Long requestedBy, String content, String referenceRefs, String model) {
        this.ticketId = ticketId;
        this.requestedBy = requestedBy;
        this.content = content;
        this.referenceRefs = referenceRefs;
        this.model = model;
    }
}
