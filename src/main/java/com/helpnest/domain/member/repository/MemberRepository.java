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

    /**
     * 배정 드롭다운용 활성 상담원 + 처리 중 건수 (CR #44). 읽기 전용 native — 박민재 ticket 테이블의
     * agent_id·status 만 참조하고, 건수 기준(ASSIGNED·IN_PROGRESS)은 TicketRepository.countActiveByAgentIds 와 같다
     */
    @Query(nativeQuery = true, value = """
            SELECT m.member_id AS "memberId", m.name AS "name", m.available AS "available",
                   COUNT(t.ticket_id) AS "activeCount"
            FROM member m
            LEFT JOIN ticket t ON t.agent_id = m.member_id AND t.status IN ('ASSIGNED', 'IN_PROGRESS')
            WHERE m.role = 'AGENT' AND m.status = 'ACTIVE'
            GROUP BY m.member_id, m.name, m.available
            ORDER BY m.name, m.member_id
            """)
    List<ConsoleAgentRow> findConsoleAgents();

    interface ConsoleAgentRow {
        Long getMemberId();

        String getName();

        boolean isAvailable();

        long getActiveCount();
    }
}
