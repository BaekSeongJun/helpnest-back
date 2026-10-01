// @owner PMJ
package com.helpnest.domain.ticket.port;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비회원 인증에 필요한 티켓 조회 (CR #34, PRD FR-AUTH-09).
 *
 * <p>비회원은 member 행이 없어 티켓 자체가 인증 대상이 된다. 조회 두 건은 S1 에서 실구현했고
 * {@link #updateGuestPassword} 만 스텁으로 남아 있다.
 *
 * <h2>로그에 무엇을 남기지 않는가</h2>
 * 이메일·비밀번호 해시·티켓 본문은 로그에 남기지 않는다(docs/10 §3.3). 시그니처에 두 값이
 * 들어오므로 파라미터를 전부 찍으면 개인정보와 자격증명이 그대로 쌓인다. 티켓번호와
 * "찾았는지 여부"만 debug 로 남긴다.
 *
 * <h2>없으면 null, 예외는 던지지 않는다</h2>
 * 두 조회 모두 실패를 예외로 알리지 않는다. 존재 여부가 호출자의 응답에 드러나면 티켓번호와
 * 이메일 조합을 확인하는 열거 수단이 되기 때문이다({@link TicketGuestPort#verifyGuest} 주석).
 * 호출자는 결과와 무관하게 같은 응답을 주고, 해시가 없을 때도 더미 해시와 한 번 비교해
 * 응답 시간까지 같게 맞춘다.
 *
 * <p>TODO(PMJ) S2 updateGuestPassword 구현 — Ticket 에 guest_password_hash 를 바꾸는 변경
 * 메서드를 추가해야 한다(현재 엔티티에는 없다). 비회원 티켓이 아니면 거부한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketGuestAdapter implements TicketGuestPort {

    private final TicketRepository ticketRepository;

    /**
     * {@code ticket_no = :ticketNo AND lower(guest_email) = lower(:email) AND customer_id IS NULL}
     * 로 조회한다. 인자가 null 이면 조회하지 않고 바로 null 을 준다 — 쿼리를 한 번 아끼려는 것이
     * 아니라, null 비교는 어차피 어떤 행에도 맞지 않아 결과가 같기 때문이다.
     */
    @Override
    public Long verifyGuest(String ticketNo, String email) {
        if (ticketNo == null || email == null) {
            return null;
        }
        Long ticketId = ticketRepository.findGuestTicketId(ticketNo, email).orElse(null);
        log.debug("[guest] verifyGuest ticketNo={} matched={}", ticketNo, ticketId != null);
        return ticketId;
    }

    @Override
    public String findGuestPasswordHash(Long ticketId) {
        if (ticketId == null) {
            return null;
        }
        String hash = ticketRepository.findGuestPasswordHash(ticketId).orElse(null);
        log.debug("[guest] findGuestPasswordHash ticketId={} found={}", ticketId, hash != null);
        return hash;
    }

    @Override
    public void updateGuestPassword(Long ticketId, String passwordHash) {
        log.info("[stub] updateGuestPassword ticketId={} — S2 구현 예정, 반영되지 않음", ticketId);
    }
}
