// @owner BSJ
package com.helpnest.domain.member.repository;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    List<Member> findByRoleAndStatusAndAvailableTrue(MemberRole role, MemberStatus status);

    /** 관리자 목록. role·status 는 null 이면 조건 없음 */
    @Query("SELECT m FROM Member m WHERE (:role IS NULL OR m.role = :role) AND (:status IS NULL OR m.status = :status)")
    Page<Member> search(MemberRole role, MemberStatus status, Pageable pageable);
}
