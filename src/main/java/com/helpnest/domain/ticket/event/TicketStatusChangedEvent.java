// @owner PMJ
package com.helpnest.domain.ticket.event;

import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 티켓 상태가 전이됐을 때 발행한다(docs/02 §5.1, §5.3 시퀀스).
 * 전이 가능 여부는 발행 전에 {@code TicketStateMachine} 이 이미 검증한 상태다.
 *
 * <h2>구독자와 처리</h2>
 * <ul>
 *   <li><b>백성준 {@code SurveyListener}</b> — {@code to == RESOLVED} 면 설문 토큰을 생성하고
 *       (이미 해결된 적 있는 재해결이면 재발급) 신수진의 {@code MailSender#sendResolvedMail}
 *       로 설문 링크 메일을 보낸다.</li>
 *   <li><b>백성준 {@code SurveyListener}</b> — {@code from == RESOLVED && to == IN_PROGRESS}
 *       (고객 재문의) 면 아직 제출되지 않은 설문을 만료시킨다. 해결이 번복됐는데 이전 설문이
 *       살아 있으면 잘못된 시점의 만족도가 집계된다.</li>
 *   <li><b>내(박민재) 알림</b> — 상태 변경을 고객·상담원에게 알린다(S2 범위).</li>
 * </ul>
 *
 * <h2>from·to 를 enum 으로 둔 이유</h2>
 * docs/02 §5.1 표는 타입을 명시하지 않는다. 구독자인 백성준이 내 {@link TicketStatus} 를
 * import 하게 되지만, §5 가 금지하는 것은 남의 <b>Entity·Repository</b> 이지 계약의 일부인
 * 값 집합이 아니다. 구독자의 분기 조건이 정확히 RESOLVED·IN_PROGRESS 인 만큼 오타가 나면
 * 설문이 조용히 생성되지 않으므로, 컴파일 시점에 걸리는 enum 이 안전하다.
 * 이견이 있으면 String 으로 바꿀 수 있다(구독 코드가 붙기 전인 S0 에 정리한다).
 *
 * <h2>actorId 가 null 일 수 있다</h2>
 * SLA 초과 자동 처리·72시간 자동 종료처럼 시스템이 전이시킨 경우 행위자가 없다
 * (docs/03 §2.1 {@code ActorType.SYSTEM} 과 같은 맥락). 구독자는 null 을 전제해야 한다.
 *
 * @param ticketId 대상 티켓의 ticket_id
 * @param from     전이 전 상태
 * @param to       전이 후 상태
 * @param actorId  전이를 일으킨 회원의 member_id. 시스템 자동 전이면 null
 */
public record TicketStatusChangedEvent(Long ticketId, TicketStatus from, TicketStatus to, Long actorId) {
}
