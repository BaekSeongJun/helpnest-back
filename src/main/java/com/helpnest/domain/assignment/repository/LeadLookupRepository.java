// @owner PMJ
package com.helpnest.domain.assignment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.helpnest.domain.ticket.entity.Ticket;

/**
 * 미배정 알림을 받을 팀장·관리자 조회.
 *
 * <h2>포트가 아니라 읽기 전용 네이티브 쿼리를 쓰는 이유</h2>
 * {@code MemberQueryPort}(백성준)에는 LEAD 목록을 얻는 메서드가 없고
 * {@code findAssignableAgents()} 는 AGENT 만 돌려준다. docs/02 §5 의 읽기 전용 예외가
 * <b>"SLA·배정(박민재)의 MEMBER 조회"를 허용 예시로 직접 명시</b>하므로 CR 없이 여기서 읽는다.
 * 같은 규정에 따라 참조 컬럼을 PR 본문에 적는다: {@code member(member_id, role, status)}.
 *
 * <p>JPQL 이 아니라 네이티브 SQL 인 이유는 JPQL 을 쓰려면 백성준의 {@code Member} 엔티티를
 * import 해야 하는데 docs/10 §3.3 이 다른 도메인 엔티티 import 를 금지하기 때문이다.
 *
 * <p>{@code Repository} 마커 인터페이스를 상속해 Ticket 의 CRUD 메서드가 딸려 오지 않게 한다 —
 * 이 인터페이스의 역할은 조회 한 건뿐이다.
 */
public interface LeadLookupRepository extends Repository<Ticket, Long> {

    /** 활성 상태인 LEAD·ADMIN 의 member_id. 둘 다 미배정 티켓을 처리할 권한이 있다 */
    @Query(value = """
            select member_id from member
            where role in ('LEAD', 'ADMIN') and status = 'ACTIVE'
            """, nativeQuery = true)
    List<Long> findLeadMemberIds();
}
