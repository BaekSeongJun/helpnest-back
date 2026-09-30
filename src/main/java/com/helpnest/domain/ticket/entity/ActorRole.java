// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * 상태 전이를 시도한 수행자의 역할. PRD 5장 전이표의 '수행자' 열을 코드로 옮긴 값이다.
 *
 * <p><b>DB 컬럼용 enum 이 아니다.</b> 전이 허용 여부 판정에만 쓰는 순수 판정용 타입이며,
 * 값 집합이 다른 아래 enum 들과 통합하거나 혼용하면 안 된다(docs/03 §2.1).
 * <ul>
 *   <li>{@code ActorType}(TICKET_HISTORY.actor_type) = MEMBER, SYSTEM, GUEST</li>
 *   <li>{@code WriterType}(TICKET_REPLY.writer_type) = CUSTOMER, GUEST, AGENT, SYSTEM</li>
 *   <li>{@code MemberRole}(MEMBER.role, 백성준 소유) = 회원 권한 등급</li>
 * </ul>
 *
 * <p>회원 권한({@code MemberRole})·비회원 여부를 이 타입으로 변환하는 책임은 S1 의
 * TicketService 에 있다. 비회원 고객(GUEST)의 재문의·종료는 고객 행위이므로
 * {@link #CUSTOMER} 로 변환해 판정한다.
 */
public enum ActorRole {

    /** 시스템 자동 처리(자동 배정, 72시간 경과 자동 종료 등). 사람의 조작이 아니다. */
    SYSTEM,

    /** 고객(회원 CUSTOMER + 비회원 GUEST). 재문의·설문 제출로 상태를 바꾼다. */
    CUSTOMER,

    /** 상담원. 배정된 티켓의 처리 시작·해결 처리를 수행한다. */
    AGENT,

    /** 상담팀장. 전체 티켓 조회와 수동 배정/재배정 권한을 갖는다. */
    LEAD,

    /** 최고관리자. LEAD 권한을 전부 포함한다. */
    ADMIN
}
