// @owner BSJ
package com.helpnest.domain.auth.repository;

import com.helpnest.domain.auth.entity.PasswordResetToken;
import com.helpnest.domain.auth.entity.PasswordResetToken.TargetType;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 같은 링크를 두 번 동시에 눌러도 한 번만 쓰이도록 행 잠금 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** 재설정 성공 시 같은 대상에게 보낸 다른 링크도 함께 사용 처리 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.targetType = :type and t.targetId = :targetId and t.usedAt is null")
    int useAllByTarget(TargetType type, Long targetId, OffsetDateTime now);
}
