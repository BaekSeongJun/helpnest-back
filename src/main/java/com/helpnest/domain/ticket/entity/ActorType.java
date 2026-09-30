// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * TICKET_HISTORY.actor_type — 변경을 수행한 주체의 종류. 단일 권위는 docs/03 §2.1 이다.
 *
 * <p>actor_id 가 NULL 인지 여부와 짝을 이룬다. {@link #MEMBER} 만 actor_id 를 가지며
 * {@link #SYSTEM} 과 {@link #GUEST} 는 NULL 이다. 따라서 actor_id 가 NULL 이어도
 * '누가 했는지 모른다'가 아니라 '사람 계정이 아니다'로 해석해야 한다.
 *
 * <p><b>{@link WriterType} 및 {@link ActorRole} 과 통합하면 안 된다.</b>
 * 셋 다 '주체'를 표현하지만 값 집합이 서로 다르다(docs/03 §2.1).
 */
public enum ActorType {

    /** 로그인 회원(고객·상담원·팀장·관리자 전부). actor_id 에 member_id 가 들어간다. */
    MEMBER,

    /** 시스템 자동 처리(자동 배정, 72시간 경과 자동 종료, SLA 위반 표시). */
    SYSTEM,

    /** 비회원 고객. 티켓의 guest_email 로 식별한다. */
    GUEST
}
