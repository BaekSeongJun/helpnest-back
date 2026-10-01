// @owner SSJ
package com.helpnest.infra.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** 메일 발송 기록·재시도 (docs/03 §3.3, docs/05 §5) */
@Getter
@Entity
@Table(name = "mail_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MailLog {

    public enum Type { RESOLVED_SURVEY, AGENT_REPLY, PASSWORD_RESET, GUEST_PASSWORD_RESET }

    /** LOGGED = 로컬(LogMailTransport), 실제 발송 없음 */
    public enum Status { SENT, FAILED, LOGGED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mail_id")
    private Long id;

    // 비밀번호 재설정 메일은 티켓 없음 → NULL
    private Long ticketId;

    @Column(nullable = false, length = 100)
    private String toEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type mailType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 500)
    private String errorMsg;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime sentAt;

    @Column(length = 200)
    private String subject;

    // 토큰 URL 포함 → 로그 출력 금지
    @Column(columnDefinition = "TEXT")
    private String body;

    @Builder
    private MailLog(Long ticketId, String toEmail, Type mailType, String subject, String body) {
        this.ticketId = ticketId;
        this.toEmail = toEmail;
        this.mailType = mailType;
        this.subject = subject;
        this.body = body;
    }

    /** transport 결과 반영 (SENT·LOGGED) */
    public void markDelivered(Status status, OffsetDateTime now) {
        this.status = status;
        this.sentAt = now;
        this.errorMsg = null;
    }

    public void markFailed(String errorMsg) {
        this.status = Status.FAILED;
        this.errorMsg = errorMsg == null || errorMsg.length() <= 500 ? errorMsg : errorMsg.substring(0, 500);
    }
}
