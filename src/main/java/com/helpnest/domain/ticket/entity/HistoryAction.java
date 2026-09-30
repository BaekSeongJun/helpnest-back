// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * TICKET_HISTORY.action — 이력에 남기는 변경의 종류. 단일 권위는 docs/03 §2.1 이다.
 *
 * <p>각 action 에 따라 from_value·to_value 에 담기는 값의 의미가 달라진다.
 * 두 컬럼은 VARCHAR(50) 이므로 enum 이름이나 member_id 를 문자열로 넣는다.
 */
public enum HistoryAction {

    /** 티켓 접수. 최초 1건만 생기며 from_value 는 NULL 이다. */
    CREATE,

    /** 상태 전이. from/to 에 {@link TicketStatus} 이름을 넣는다. */
    STATUS_CHANGE,

    /** 최초 배정. from_value 는 NULL, to_value 에 배정된 member_id 를 넣는다. */
    ASSIGN,

    /** 재배정. from/to 에 이전·새 담당자의 member_id 를 넣는다. */
    REASSIGN,

    /** 우선순위 변경(AI 분류 또는 팀장 수동). from/to 에 {@link TicketPriority} 이름을 넣는다. */
    PRIORITY_CHANGE,

    /** 유형 변경. from/to 에 {@link TicketCategory} 이름을 넣는다. */
    CATEGORY_CHANGE
}
