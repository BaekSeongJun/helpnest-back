// @owner PMJ
package com.helpnest.domain.ticket.port;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * S0 스텁 — 실제 반영은 <b>S1</b> TicketService·AssignmentService 에서 구현한다.
 *
 * <p>두 메서드가 모두 void 라 호출자는 반영이 안 됐다는 사실을 알 수 없다. 그래서 스텁 호출을
 * debug 가 아니라 info 로 남긴다. 신수진이 S1 에 AI 분류를 붙이고 "분류했는데 티켓이 안 바뀐다"를
 * 디버깅하는 상황을 만들지 않으려는 것이다.
 *
 * <p>TODO(PMJ) S1 applyClassification 구현 — 근거를 미리 정리해 두어 S1 에 재조사가 필요 없게 한다.
 * <ol>
 *   <li>값 검증: category·priority·sentiment 를 docs/03 §2.1 enum 으로 변환. 목록 밖 값이면 거부
 *       (DDL 에 CHECK 제약이 없어 무결성 책임이 이 계층에 있다)</li>
 *   <li>Ticket.applyClassification(...) 반영 후 SlaPolicy 로 기한 재계산 →
 *       Ticket.updateFirstResponseDueAt(...). PRD 6.1 "AI 분류로 우선순위가 바뀌면 재계산"</li>
 *   <li>우선순위가 바뀌었으면 TicketHistory 에 PRIORITY_CHANGE(actorType=SYSTEM) 기록</li>
 *   <li>자동 배정(PRD 6.2): status=RECEIVED 인 티켓만 대상. 후보는
 *       MemberQueryPort.findAssignableAgents()(role=AGENT·status=ACTIVE·available=true).
 *       부하는 각 상담원의 ASSIGNED+IN_PROGRESS 티켓 수, 동률이면 last_assigned_at 오래된 순.
 *       대상 티켓은 SELECT FOR UPDATE 비관적 락으로 잡아 중복 배정을 막는다.
 *       배정 성공 시 MemberQueryPort.touchLastAssigned(agentId) 와 TicketAssignedEvent 발행</li>
 *   <li>후보가 없으면 RECEIVED 를 유지하고 LEAD 에게 NotificationPort.notify(type=UNASSIGNED)</li>
 * </ol>
 * applyClassificationFailed 는 기본값(ETC·NORMAL)을 그대로 두고 4·5번만 수행한다(docs/05 §3.1 7번).
 */
@Slf4j
@Component
public class TicketClassificationAdapter implements TicketClassificationPort {

    @Override
    public void applyClassification(Long ticketId, String category, String priority, String sentiment) {
        log.info("[stub] applyClassification ticketId={} category={} priority={} sentiment={} — S1 구현 예정, 반영되지 않음",
                ticketId, category, priority, sentiment);
    }

    @Override
    public void applyClassificationFailed(Long ticketId) {
        log.info("[stub] applyClassificationFailed ticketId={} — S1 구현 예정, 자동 배정되지 않음", ticketId);
    }
}
