// @owner PMJ
package com.helpnest.domain.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.helpnest.domain.ticket.entity.TicketHistory;

/**
 * 티켓 이력 저장·조회. 추가 전용 테이블이므로 실제로 쓰는 것은 {@code save} 와
 * 티켓별 목록 조회뿐이다. 목록 조회 메서드는 상세 화면 명세가 확정되는 S1 에서 추가한다.
 */
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {
}
