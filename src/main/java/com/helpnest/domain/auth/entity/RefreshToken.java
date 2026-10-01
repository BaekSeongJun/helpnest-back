// @owner BSJ
package com.helpnest.domain.auth.entity;

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
import org.hibernate.annotations.CreationTimestamp;

/** 원문 토큰은 쿠키에만, DB 에는 SHA-256 해시만 저장 */
@Getter
@Entity
@Table(name = "refresh_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    // auth → member 엔티티 결합을 피하려고 id 로만 참조
    @Column(nullable = false)
    private Long memberId;

    @Column(nullable = false, unique = true, length = 200)
    private String tokenHash;

    @Column(nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /** 회전(재발급)으로 폐기된 시각. 로그아웃·일괄 폐기는 NULL → 회전 유예 대상 아님 */
    private OffsetDateTime rotatedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public RefreshToken(Long memberId, String tokenHash, OffsetDateTime expiresAt) {
        this.memberId = memberId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(OffsetDateTime now) {
        return !revoked && expiresAt.isAfter(now);
    }

    public void revoke() {
        this.revoked = true;
    }

    /** 재발급에 쓰여 폐기 */
    public void rotate(OffsetDateTime now) {
        this.revoked = true;
        this.rotatedAt = now;
    }

    /**
     * 방금 회전된 토큰인지. 회전 응답(Set-Cookie)을 받기 전에 페이지를 떠났거나 탭 두 개가 동시에 복원하면
     * 브라우저가 옛 쿠키를 다시 보낸다 — 이것은 탈취가 아니므로 grace 안이면 재발급을 허용한다.
     */
    public boolean isRecentlyRotated(OffsetDateTime now, Duration grace) {
        return rotatedAt != null && rotatedAt.plus(grace).isAfter(now) && expiresAt.isAfter(now);
    }
}
