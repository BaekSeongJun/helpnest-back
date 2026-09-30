// @owner PMJ
package com.helpnest.domain.ticket.port;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * S0 스텁 — 실제 확인·변경은 <b>S1</b> 에서 구현한다(PRD FR-AUTH-09, 로드맵 S1 백엔드 항목).
 *
 * <p><b>로그에 이메일과 비밀번호 해시를 남기지 않는다</b>(docs/10 §3.3). 스텁이라도 시그니처에
 * 두 값이 들어오므로, 다른 스텁처럼 파라미터를 전부 찍으면 개인정보가 그대로 로그에 쌓인다.
 * verifyGuest 는 ticketNo 만, updateGuestPassword 는 ticketId 만 남긴다.
 *
 * <p>TODO(PMJ) S1 verifyGuest 구현 — {@code ticket_no = :ticketNo AND lower(guest_email) =
 * lower(:email)} 로 조회해 ticket_id 를 반환하고 없으면 null. DDL 의 idx_ticket_guest_email 이
 * lower(guest_email) 부분 인덱스이므로 반드시 lower() 로 비교해야 인덱스를 탄다.
 * 회원 티켓(customer_id IS NOT NULL)은 대상이 아니므로 제외한다.
 *
 * <p>TODO(PMJ) S1 updateGuestPassword 구현 — Ticket 에 guest_password_hash 를 바꾸는 변경
 * 메서드를 추가해야 한다(현재 엔티티에는 없다). 비회원 티켓이 아니면 거부한다.
 */
@Slf4j
@Component
public class TicketGuestAdapter implements TicketGuestPort {

    @Override
    public Long verifyGuest(String ticketNo, String email) {
        log.debug("[stub] verifyGuest ticketNo={}", ticketNo);
        return null;
    }

    @Override
    public void updateGuestPassword(Long ticketId, String passwordHash) {
        log.info("[stub] updateGuestPassword ticketId={} — S1 구현 예정, 반영되지 않음", ticketId);
    }
}
