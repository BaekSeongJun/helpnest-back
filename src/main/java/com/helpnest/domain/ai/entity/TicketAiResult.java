// @owner SSJ
package com.helpnest.domain.ai.entity;

import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import com.helpnest.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI-1 분류 결과. 티켓당 1건 (docs/03 §3.3) */
@Getter
@Entity
@Table(name = "ticket_ai_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketAiResult extends BaseTimeEntity {

    public enum Status { SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ai_result_id")
    private Long id;

    // 다른 도메인 엔티티 import 금지 → id 로만 참조
    @Column(nullable = false, unique = true)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Urgency urgency;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Sentiment sentiment;

    @Column(length = 500)
    private String summary;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 100)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    private String rawResponse;

    private Integer latencyMs;

    private Long overriddenBy;

    @Builder
    private TicketAiResult(Long ticketId, Category category, Urgency urgency, Sentiment sentiment, String summary,
            BigDecimal confidence, Status status, String model, String rawResponse, Integer latencyMs) {
        this.ticketId = ticketId;
        this.category = category;
        this.urgency = urgency;
        this.sentiment = sentiment;
        this.summary = summary;
        this.confidence = confidence;
        this.status = status;
        this.model = model;
        this.rawResponse = rawResponse;
        this.latencyMs = latencyMs;
    }

    /** 재분류 성공 — 티켓당 1행이라 기존 행을 덮어쓴다 */
    public void updateSuccess(Category category, Urgency urgency, Sentiment sentiment, String summary,
            BigDecimal confidence, String model, String rawResponse, Integer latencyMs) {
        this.category = category;
        this.urgency = urgency;
        this.sentiment = sentiment;
        this.summary = summary;
        this.confidence = confidence;
        this.status = Status.SUCCESS;
        this.model = model;
        this.rawResponse = rawResponse;
        this.latencyMs = latencyMs;
    }

    /** 실패 시 이전 분류값은 지워 화면에 낡은 결과가 남지 않게 한다 */
    public void markFailed(String model, Integer latencyMs) {
        updateSuccess(null, null, null, null, null, model, null, latencyMs);
        this.status = Status.FAILED;
    }

    /** 상담원 수동 분류 수정 (docs/05 §3.3) */
    public void markOverridden(Long memberId) {
        this.overriddenBy = memberId;
    }
}
