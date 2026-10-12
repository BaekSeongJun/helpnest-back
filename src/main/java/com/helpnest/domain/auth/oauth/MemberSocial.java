// @owner BSJ
package com.helpnest.domain.auth.oauth;

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

/** 회원 ↔ 소셜 계정 연결. (provider, providerUserId) 가 로그인 키 */
@Getter
@Entity
@Table(name = "member_social")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberSocial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "social_id")
    private Long id;

    @Column(nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OAuthProvider provider;

    @Column(nullable = false, length = 100)
    private String providerUserId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public MemberSocial(Long memberId, OAuthProvider provider, String providerUserId) {
        this.memberId = memberId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
