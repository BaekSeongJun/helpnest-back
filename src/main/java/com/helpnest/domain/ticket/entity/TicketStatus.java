// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * 티켓 처리 상태.
 * 값 집합의 단일 권위는 docs/03 §2.1 이며, 상태 사이의 전이 규칙은 PRD 5장 전이표
 * ({@link com.helpnest.domain.ticket.service.TicketStateMachine})가 관리한다.
 *
 * <p>TICKET 테이블에 CHECK 제약이 없으므로 값 무결성 책임은 전부 JPA 계층에 있다.
 * Entity 매핑 시 {@code @Enumerated(EnumType.STRING)} 을 반드시 붙인다.
 */
public enum TicketStatus {

    /** 접수 완료. 아직 담당 상담원이 없는 초기 상태. */
    RECEIVED,

    /** 담당 상담원 배정 완료. 아직 처리 시작 전. */
    ASSIGNED,

    /** 상담원이 처리 시작(또는 첫 답변)한 상태. */
    IN_PROGRESS,

    /** 해결 처리 완료. 고객의 재문의 또는 종료를 기다리는 상태. */
    RESOLVED,

    /** 종료. 더 이상 전이할 수 없는 최종 상태(설문 제출 또는 72시간 경과). */
    CLOSED
}
