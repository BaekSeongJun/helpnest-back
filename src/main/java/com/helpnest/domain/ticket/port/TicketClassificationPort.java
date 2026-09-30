// @owner PMJ
package com.helpnest.domain.ticket.port;

/**
 * 호출자: 신수진(AI-1 분류 결과 반영) (docs/02 §5.2, docs/05 §3.1)
 *
 * <p>파라미터를 enum 이 아니라 String 으로 받는다. 호출자가 내 {@code TicketCategory}·
 * {@code TicketPriority}·{@code Sentiment} 를 import 하면 도메인 경계가 흐려지고, DDL 저장
 * 타입도 VARCHAR 다. 값 집합의 단일 권위는 docs/03 §2.1 이며 enum 이름을 그대로 넘긴다.
 * 목록에 없는 값이 오면 이 포트의 구현이 거부한다(무결성 책임은 소유자인 내 쪽에 있다).
 */
public interface TicketClassificationPort {

    /**
     * 분류 결과를 반영한다. 반영 후 우선순위에 맞춰 SLA 기한을 재계산하고 자동 배정까지 수행한다
     * (docs/05 §3.1 6번, PRD 6.1·6.2). 자동 배정은 status=RECEIVED 인 티켓만 대상이다.
     *
     * @param ticketId  대상 티켓
     * @param category  docs/03 §2.1 category 값(DELIVERY·REFUND·EXCHANGE·PAYMENT·ACCOUNT·SERVICE_ERROR·ETC)
     * @param priority  docs/03 §2.1 priority 값(URGENT·HIGH·NORMAL·LOW).
     *                  감정 보정(NEGATIVE 면 1단계 상향)은 호출자가 끝낸 뒤의 최종값이다(docs/05 §3.1 5번)
     * @param sentiment docs/03 §2.1 sentiment 값(NEGATIVE·NEUTRAL·POSITIVE)
     */
    void applyClassification(Long ticketId, String category, String priority, String sentiment);

    /**
     * 분류 실패·타임아웃을 알린다(docs/05 §3.1 7번). 티켓은 기본값(ETC·NORMAL)을 유지한 채
     * 자동 배정만 진행하므로, 분류가 실패해도 티켓이 배정되지 않고 방치되는 일은 없다.
     *
     * @param ticketId 대상 티켓
     */
    void applyClassificationFailed(Long ticketId);
}
