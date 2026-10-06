// @owner BSJ
package com.helpnest.domain.survey.event;

/**
 * 고객이 만족도 설문을 제출했을 때 발행한다(docs/02 §5.1).
 * 구독자: 박민재 {@code TicketCloseListener} — 해당 티켓을 CLOSED 로 전이한다.
 * 발행: {@code SurveyService.submit} — 제출 트랜잭션 안에서 발행하므로 구독자는 AFTER_COMMIT 으로 받는다.
 *
 * @param ticketId 설문 대상 티켓의 ticket_id
 * @param rating   별점 1~5
 */
public record SurveySubmittedEvent(Long ticketId, int rating) {
}
