// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * 티켓 우선순위. 값 집합의 단일 권위는 docs/03 §2.1 이다.
 *
 * <p>SLA_POLICY 테이블의 PK 이자 TICKET.priority 의 FK 값이므로, 이 enum 에 상수를 추가하면
 * {@code sla_policy} 에 같은 이름의 행을 넣는 마이그레이션도 함께 필요하다(없으면 FK 위반).
 * 응답 기한은 우선순위마다 다르며 그 값은
 * {@link com.helpnest.domain.sla.entity.SlaPolicy} 가 관리한다(PRD 6.1).
 *
 * <p>선언 순서는 긴급도 내림차순이므로 {@link Enum#compareTo} 로 정렬하면 급한 티켓이 앞에 온다.
 * 단 DB 저장은 {@code @Enumerated(EnumType.STRING)} 으로 이름을 쓰므로 순서를 바꿔도 안전하다.
 */
public enum TicketPriority {

    /** 최우선. 첫 응답 기한 1시간(PRD 6.1). */
    URGENT,

    /** 높음. 첫 응답 기한 4시간. */
    HIGH,

    /** 기본값. 첫 응답 기한 24시간. AI 분류 전 신규 티켓이 갖는 값이다. */
    NORMAL,

    /** 낮음. 첫 응답 기한 48시간. */
    LOW
}
