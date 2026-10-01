// @owner BSJ
package com.helpnest.domain.auth.entity;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 재설정 링크 토큰. 원문은 메일 링크에만, DB 에는 SHA-256 해시만 (FR-AUTH-07, 09) */
@Getter
@Entity
@Table(name = "password_reset_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

    /** MEMBER → member_id, GUEST_TICKET → ticket_id */
    public enum TargetType {
        MEMBER, GUEST_TICKET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reset_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, unique = true, length = 200)
    private String tokenHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    private OffsetDateTime usedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public PasswordResetToken(TargetType targetType, Long targetId, String tokenHash, OffsetDateTime expiresAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(TargetType type, OffsetDateTime now) {
        return targetType == type && usedAt == null && expiresAt.isAfter(now);
    }
}
