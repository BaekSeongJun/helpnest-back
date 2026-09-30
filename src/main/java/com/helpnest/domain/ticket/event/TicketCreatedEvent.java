// @owner PMJ
package com.helpnest.domain.ticket.event;

/**
 * 티켓이 새로 접수됐을 때 발행한다(docs/02 §5.1, §5.3 시퀀스).
 *
 * <h2>구독자와 처리</h2>
 * 신수진의 {@code AiClassifyListener} 가
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + {@code @Async} 로 받아
 * LLM 분류(유형·긴급도·감정)를 수행하고, 결과를 {@code TicketClassificationPort} 로 되돌린다.
 *
 * <p>AFTER_COMMIT 인 이유: 분류 리스너는 비동기 스레드에서 {@code ticketId} 로 티켓을 다시
 * 조회한다. 커밋 전에 발행하면 아직 보이지 않는 행을 찾게 되고, 반대로 분류가 먼저 반영된 뒤
 * 접수 트랜잭션이 롤백되면 존재하지 않는 티켓의 분류 결과가 남는다.
 *
 * <h2>필드가 ticketId 하나뿐인 이유</h2>
 * 분류에 필요한 제목·본문은 구독자가 {@code TicketQueryPort#getTicketSummary} 로 조회한다.
 * 이벤트에 본문을 실으면 커밋 시점의 값이 고정돼, 발행과 처리 사이에 티켓이 수정됐을 때
 * 낡은 내용으로 분류하게 된다. 식별자만 넘기고 최신 상태를 읽는 편이 안전하다.
 *
 * <p>발행 위치는 S1 의 {@code TicketService} 접수 로직이다(이 태스크 범위는 계약 정의까지).
 *
 * @param ticketId 접수된 티켓의 ticket_id
 */
public record TicketCreatedEvent(Long ticketId) {
}
