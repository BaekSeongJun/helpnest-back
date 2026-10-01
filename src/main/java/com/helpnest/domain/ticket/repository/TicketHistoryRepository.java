// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.helpnest.domain.ticket.entity.TicketHistory;

/**
 * 티켓 이력 저장·조회. 추가 전용 테이블이므로 실제로 쓰는 것은 {@code save} 와
 * 티켓별 목록 조회뿐이다.
 */
public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    /**
     * 티켓의 전체 이력을 쌓인 순서대로 (CS-02 우측 '상태 이력' 패널).
     *
     * <p>정렬 기준이 created_at 인 이유는 화면이 시간순 타임라인이기 때문이다. history_id 순과
     * 거의 같지만, 한 트랜잭션에서 이력 두 건을 남기는 경우(수동 분류 수정의 CATEGORY_CHANGE +
     * PRIORITY_CHANGE)에는 created_at 이 동일할 수 있어 그 둘의 상대 순서는 보장하지 않는다.
     * 같은 시각의 두 변경은 화면에서 어느 쪽이 먼저 보이든 의미가 달라지지 않는다.
     */
    List<TicketHistory> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
