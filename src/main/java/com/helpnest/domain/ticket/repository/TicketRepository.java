// @owner PMJ
package com.helpnest.domain.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.helpnest.domain.ticket.entity.Ticket;

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
}
