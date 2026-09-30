// @owner PMJ
package com.helpnest.domain.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.helpnest.domain.ticket.entity.Ticket;

/**
 * 티켓 조회·저장.
 *
 * <p>S0 범위에서는 {@link JpaRepository} 기본 메서드만 쓴다. 목록 검색·SLA 감시 대상 조회 같은
 * 커스텀 쿼리는 화면 명세(docs/09)와 API 명세(docs/04)가 요구하는 정렬·필터가 확정되는
 * S1 에서 추가한다. 통계·목록용 읽기 전용 JPQL 은 이 패키지에서 허용되지만 다른 도메인
 * 테이블에 대한 쓰기는 반드시 포트를 거쳐야 한다(docs/02 §5).
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {
}
