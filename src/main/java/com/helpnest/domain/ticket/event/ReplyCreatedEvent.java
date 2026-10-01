// @owner PMJ
package com.helpnest.domain.ticket.event;

/**
 * 티켓에 답변 또는 내부 메모가 등록됐을 때 발행한다(docs/02 §5.1).
 *
 * <h2>구독자와 처리</h2>
 * 내(박민재) {@code NotificationListener} 가 받아 {@code writerType} 과 {@code isInternal}
 * 조합으로 알림 대상을 정한다(S2 범위).
 * <ul>
 *   <li>고객 답글(CUSTOMER·GUEST) → 담당 상담원에게 알림</li>
 *   <li>상담원 공개 답변(AGENT + {@code isInternal == false}) → 회원 고객에게 웹 알림 +
 *       신수진의 {@code MailSender#sendAgentReplyMail} 로 메일 발송</li>
 *   <li><b>내부 메모({@code isInternal == true}) → 고객에게 아무것도 보내지 않는다.</b>
 *       이 플래그를 놓치면 상담원끼리 주고받은 메모가 그대로 고객 메일로 나간다
 *       ({@code TicketReply#isInternal()} 과 같은 이유).</li>
 * </ul>
 *
 * <h2>writerType 이 String 인 이유</h2>
 * docs/02 §5.1 표는 타입을 명시하지 않는다. 값 집합의 단일 권위는 docs/03 §2.1 이며
 * {@code WriterType} enum 이름(CUSTOMER·GUEST·AGENT·SYSTEM)을 그대로 실어 보낸다.
 * {@code TicketClassificationPort} 가 분류 값을 String 으로 받는 것과 같은 규약이다.
 * (이 이벤트의 구독자는 현재 나뿐이라 enum 으로 바꿔도 경계 문제는 없다. 구독 코드가
 * 붙기 전인 S0 에 팀 합의로 정리할 수 있다.)
 *
 * @param ticketId   답변이 달린 티켓의 ticket_id
 * @param replyId    등록된 답변의 reply_id
 * @param writerType docs/03 §2.1 writer_type 값(CUSTOMER·GUEST·AGENT·SYSTEM)
 * @param isInternal 내부 메모 여부. true 면 고객에게 노출하는 알림·메일을 보내지 않는다
 */
public record ReplyCreatedEvent(Long ticketId, Long replyId, String writerType, boolean isInternal) {
}
