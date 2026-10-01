// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.ticket.entity.Ticket;

/**
 * 이력 화면에 수행자 이름을 붙이기 위한 회원 이름 조회.
 *
 * <h2>포트가 아니라 읽기 전용 네이티브 쿼리를 쓰는 이유</h2>
 * {@code MemberQueryPort.getMember(id)} 는 한 건씩만 돌려주므로 이력 N 건이면 N 번 호출해야 하고
 * (N+1), 회원이 없으면 {@code MEMBER_NOT_FOUND} 를 던져 이력 조회 전체가 실패한다. 이력은 과거
 * 기록이라 수행자가 지금 조회되지 않아도 그 행은 보여야 한다. docs/02 §5 가 "SLA·배정(박민재)의
 * MEMBER 조회"를 읽기 전용 예외로 직접 명시하므로 CR 없이 여기서 읽는다. 같은 규정에 따라
 * 참조 컬럼을 PR 본문에 적는다: {@code member(member_id, name)}.
 *
 * <p>JPQL 이 아닌 네이티브 SQL 인 이유는 JPQL 이면 백성준의 {@code Member} 엔티티를 import 해야
 * 하는데 docs/10 §3.3 이 이를 금지하기 때문이다({@code LeadLookupRepository} 와 같은 판단).
 *
 * <p>{@code Repository} 마커를 상속해 Ticket 의 CRUD 메서드가 딸려 오지 않게 한다.
 */
public interface MemberNameLookupRepository extends Repository<Ticket, Long> {

    /**
     * 주어진 member_id 들의 이름. 없는 id 는 결과에서 빠지므로 호출자가 null 로 처리한다.
     *
     * <p>별칭에 따옴표를 쓴 것은 PostgreSQL 이 따옴표 없는 식별자를 소문자로 접기 때문이다.
     * 인터페이스 프로젝션은 getter 이름({@code getMemberId})으로 컬럼을 찾으므로 대소문자가
     * 보존돼야 한다.
     */
    @Query(value = """
            select member_id as "memberId", name as "name"
            from member
            where member_id in (:memberIds)
            """, nativeQuery = true)
    List<MemberName> findNames(@Param("memberIds") Collection<Long> memberIds);

    /**
     * 회원 1명의 이름. 답글 작성자처럼 한 건만 필요한 자리에서 쓴다.
     *
     * <p>{@link #findNames} 를 재사용하는 이유는 같은 쿼리를 두 번 적지 않기 위해서다.
     * {@code actorId} 가 null(SYSTEM·GUEST)이면 조회하지 않고 null 을 준다.
     */
    default String findName(Long memberId) {
        if (memberId == null) {
            return null;
        }
        return findNames(List.of(memberId)).stream()
                .findFirst()
                .map(MemberName::getName)
                .orElse(null);
    }
}
