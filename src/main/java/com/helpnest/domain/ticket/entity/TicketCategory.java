// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * 문의 유형. 값 집합의 단일 권위는 docs/03 §2.1(PRD Q4)이다.
 *
 * <p>신수진의 LLM 분류 결과가 이 값으로 들어오므로(docs/05), 모델이 목록에 없는 문자열을
 * 반환하면 {@link #ETC} 로 떨어뜨리는 책임은 AI 응답 파싱 계층에 있다.
 * TICKET.category 는 NOT NULL DEFAULT 'ETC' 이다.
 */
public enum TicketCategory {

    /** 배송 관련(지연, 오배송, 배송지 변경). */
    DELIVERY,

    /** 환불 요청. */
    REFUND,

    /** 교환 요청. */
    EXCHANGE,

    /** 결제 관련(결제 실패, 중복 결제, 영수증). */
    PAYMENT,

    /** 계정 관련(로그인 불가, 회원정보 변경, 탈퇴). */
    ACCOUNT,

    /** 서비스 장애·오류 신고. */
    SERVICE_ERROR,

    /** 위 어디에도 속하지 않는 문의. 분류 실패 시의 기본값이다. */
    ETC
}
