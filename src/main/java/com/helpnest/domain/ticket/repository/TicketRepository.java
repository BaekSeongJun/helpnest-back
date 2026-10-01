// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.ticket.entity.Ticket;

import jakarta.persistence.LockModeType;

/**
 * 티켓 조회·저장.
 *
 * <p>목록 검색·SLA 감시 대상 조회 같은 커스텀 쿼리는 화면 명세(docs/09)와 API 명세(docs/04)가
 * 요구하는 정렬·필터를 구현하는 S1 후속 태스크에서 추가한다. 통계·목록용 읽기 전용 JPQL 은
 * 이 패키지에서 허용되지만 다른 도메인 테이블에 대한 쓰기는 반드시 포트를 거쳐야 한다
 * (docs/02 §5).
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * 티켓번호 일련번호를 발급한다. 시퀀스는 V202609301110__PMJ_create_ticket.sql 에서 생성한다.
     *
     * <p>엔티티 식별자가 아니라 사람이 읽는 번호를 만드는 값이므로 {@code @GeneratedValue} 가
     * 아니라 명시적으로 당겨 쓴다. 시퀀스 증가는 트랜잭션 롤백에 영향받지 않는다 —
     * 번호 중복을 막는 것이 목적이므로 이것이 의도된 동작이다.
     */
    @Query(value = "SELECT nextval('ticket_no_seq')", nativeQuery = true)
    long nextTicketNoSeq();

    /**
     * 배정·분류 반영용 비관적 락 조회 (SELECT ... FOR UPDATE).
     *
     * <p>자동 배정은 "읽고 → 판단하고 → 쓰는" 흐름이라 같은 티켓에 두 번 돌면 나중 것이 앞의
     * 배정을 덮어쓴다. AI 분류 리스너는 비동기이고 팀장의 수동 배정은 사람이 아무 때나 누르므로
     * 두 흐름이 겹칠 수 있다. 티켓 1행을 잠가 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /**
     * 상담원별 처리 중 티켓 수 (PRD 6.2 최소 부하 배정의 입력).
     *
     * <p>한 건도 없는 상담원은 결과에 나오지 않는다(group by 특성) — 호출자가 0 으로 채워야 한다.
     * 결과를 0 으로 채우는 쪽이 쿼리에 outer join 을 넣는 것보다 단순하다.
     */
    @Query("""
            select new com.helpnest.domain.ticket.repository.AgentLoad(t.agentId, count(t))
            from Ticket t
            where t.agentId in :agentIds and t.status in (
                com.helpnest.domain.ticket.entity.TicketStatus.ASSIGNED,
                com.helpnest.domain.ticket.entity.TicketStatus.IN_PROGRESS)
            group by t.agentId
            """)
    List<AgentLoad> countActiveByAgentIds(@Param("agentIds") List<Long> agentIds);
}
