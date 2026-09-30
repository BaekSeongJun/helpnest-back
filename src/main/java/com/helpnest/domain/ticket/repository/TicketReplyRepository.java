// @owner PMJ
package com.helpnest.domain.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.helpnest.domain.ticket.entity.TicketReply;

/**
 * 티켓 답변 저장·조회.
 *
 * <p>S1 에서 조회 메서드를 추가할 때 고객용과 상담원용을 반드시 분리한다. 고객용은
 * {@code isInternal=false} 조건이 빠지면 내부 메모가 그대로 노출되므로, 조건을 호출부의
 * 책임으로 남기지 말고 쿼리 메서드 이름에 못박는 편이 안전하다.
 */
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {
}
