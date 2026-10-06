// @owner BSJ
package com.helpnest.domain.survey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 만족도 설문 (03 §3.1). 티켓당 한 행이며, 재해결 시 같은 행의 토큰을 새로 발급한다(FR-SRV-06).
 * ticket 은 박민재 소유라 Entity 연관 없이 id 로만 참조한다(02 §5).
 */
@Getter
@Entity
@Table(name = "survey")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Survey {

    /** 발송 후 응답 가능 기간 */
    public static final Duration VALID_FOR = Duration.ofHours(72);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "survey_id")
    private Long id;

    @Column(nullable = false, updatable = false, unique = true)
    private Long ticketId;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** 1~5. DB 가 SMALLINT 라 Short — ddl-auto=validate 에서 Integer 는 타입 불일치로 기동이 실패한다 */
    private Short rating;

    @Column(length = 1000)
    private String comment;

    @Column(nullable = false)
    private OffsetDateTime sentAt;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime submittedAt;

    public Survey(Long ticketId, String token, OffsetDateTime now) {
        this.ticketId = ticketId;
        issue(token, now);
    }

    /** 재해결 — 같은 행에 새 토큰을 발급하고 이전 응답을 지운다 */
    public void reissue(String token, OffsetDateTime now) {
        issue(token, now);
        this.rating = null;
        this.comment = null;
        this.submittedAt = null;
    }

    /** 재문의 — 아직 제출 전인 설문만 즉시 만료한다. 제출된 응답은 그대로 둔다 */
    public void expire(OffsetDateTime now) {
        if (submittedAt == null) {
            this.expiresAt = now;
        }
    }

    private void issue(String token, OffsetDateTime now) {
        this.token = token;
        this.sentAt = now;
        this.expiresAt = now.plus(VALID_FOR);
    }
}
