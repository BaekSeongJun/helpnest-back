// @owner PMJ
package com.helpnest.domain.ticket.port;

/**
 * 호출자: 백성준(비회원 조회 비밀번호 재설정) (docs/02 §5.2, PRD FR-AUTH-09)
 *
 * <p>비회원은 member 행이 없어 티켓 자체가 인증 대상이 된다. 티켓은 내 소유이므로 백성준의
 * 인증 흐름이 {@code ticket.guest_password_hash} 를 직접 쓰지 않고 이 포트를 거친다.
 */
public interface TicketGuestPort {

    /**
     * 티켓번호와 이메일이 같은 비회원 티켓을 가리키는지 확인한다. 재설정 링크를 메일로 보내기
     * 전에 대상이 존재하는지 판단하는 용도다(PRD FR-AUTH-09).
     *
     * @param ticketNo 티켓번호(HN-20261002-000123)
     * @param email    접수 시 입력한 비회원 이메일. 대소문자를 구분하지 않는다
     *                 (DDL 의 idx_ticket_guest_email 이 lower(guest_email) 인덱스다)
     * @return 일치하는 티켓의 ticket_id. 없으면 {@code null}.
     *         <b>예외를 던지지 않는 이유</b>: 존재 여부가 응답으로 드러나면 티켓번호·이메일 조합을
     *         확인하는 계정 열거 수단이 된다. 호출자는 결과와 무관하게 같은 응답을 주어야 한다.
     */
    Long verifyGuest(String ticketNo, String email);

    /**
     * 비회원 조회 비밀번호를 교체한다.
     *
     * @param ticketId     {@link #verifyGuest} 로 확인한 티켓
     * @param passwordHash BCrypt 해시. 원문 비밀번호를 넘기지 않는다 — 해싱은 인증 정책을 가진
     *                     호출자(백성준) 책임이고, 원문이 도메인 경계를 넘으면 로그에 남을 위험이 커진다
     */
    void updateGuestPassword(Long ticketId, String passwordHash);
}
