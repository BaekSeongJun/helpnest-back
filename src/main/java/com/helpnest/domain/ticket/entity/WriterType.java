// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * TICKET_REPLY.writer_type — 답변 작성자의 구분. 단일 권위는 docs/03 §2.1 이다.
 *
 * <p><b>{@code ActorType} 과 통합하면 안 된다.</b> 값 집합이 다르다
 * (여기에는 CUSTOMER·AGENT 가 있고 ActorType 에는 MEMBER 가 있다).
 * 답변은 '누가 썼는지'를 역할로 남겨야 고객 노출 여부를 판단할 수 있고,
 * 이력은 '회원인지 시스템인지'만 필요하기 때문이다.
 *
 * <p>{@link #CUSTOMER} 와 {@link #GUEST} 는 둘 다 고객 답변이지만 작성자 식별 방식이 다르다.
 * CUSTOMER 는 writer_id 가 있고 GUEST 는 writer_id 가 NULL 이다.
 */
public enum WriterType {

    /** 로그인 회원 고객. writer_id 에 member_id 가 들어간다. */
    CUSTOMER,

    /** 비회원 고객. writer_id 는 NULL 이며 티켓의 guest_email 로 식별한다. */
    GUEST,

    /** 상담원·팀장·관리자. is_internal=true 인 내부 메모도 이 타입이다. */
    AGENT,

    /** 시스템 자동 등록(자동 종료 안내 등). writer_id 는 NULL 이다. */
    SYSTEM
}
