// @owner BSJ
package com.helpnest.domain.auth.repository;

import com.helpnest.domain.auth.entity.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 비밀번호 변경·재사용 탐지 시 전체 폐기 */
    @Modifying
    @Query("update RefreshToken t set t.revoked = true where t.memberId = :memberId and t.revoked = false")
    int revokeAllByMemberId(Long memberId);
}
