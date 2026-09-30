// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * 티켓이 접수된 경로. 값 집합의 단일 권위는 docs/03 §2.1 이다.
 * TICKET.channel 은 NOT NULL DEFAULT 'WEB' 이다.
 */
public enum TicketChannel {

    /** 웹 문의 폼으로 직접 접수(회원·비회원 공통). */
    WEB,

    /** 1:1 채팅 상담이 티켓으로 전환되어 생성(CHAT_ROOM.status=CONVERTED, S3). */
    CHAT
}
