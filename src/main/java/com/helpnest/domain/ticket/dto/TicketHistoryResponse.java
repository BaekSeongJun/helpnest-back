// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.time.OffsetDateTime;

import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;

/**
 * 상태·배정·분류 변경 이력 한 건 (CS-02 우측 패널).
 *
 * @param fromValue action 에 따라 의미가 달라지는 문자열. STATUS_CHANGE 면 TicketStatus 이름,
 *                  ASSIGN·REASSIGN 이면 member_id 문자열이다(HistoryAction 상수 주석).
 *                  타입을 좁히지 않는 이유는 action 별로 다른 enum 이 들어가기 때문이다.
 * @param actorName actorType 이 MEMBER 가 아니면 null (시스템 자동 처리)
 */
public record TicketHistoryResponse(
        Long historyId,
        HistoryAction action,
        String fromValue,
        String toValue,
        String actorName,
        ActorType actorType,
        String memo,
        OffsetDateTime createdAt) {
}
