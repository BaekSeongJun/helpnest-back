// @owner BSJ
package com.helpnest.domain.survey.event;

/**
 * 고객이 만족도 설문을 제출했을 때 발행한다(docs/02 §5.1).
 * 구독자: 박민재 {@code TicketCloseListener} — 해당 티켓을 CLOSED 로 전이한다.
 * 발행 코드(SurveyService)는 S2 설문 기능에서 붙는다. S0 에는 계약만 둔다.
 *
 * @param ticketId 설문 대상 티켓의 ticket_id
 * @param rating   별점 1~5
 */
public record SurveySubmittedEvent(Long ticketId, int rating) {
}
