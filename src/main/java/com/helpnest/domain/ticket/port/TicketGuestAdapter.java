// @owner PMJ
package com.helpnest.domain.ticket.port;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비회원 인증에 필요한 티켓 조회 (CR #34, PRD FR-AUTH-09).
 *
 * <p>비회원은 member 행이 없어 티켓 자체가 인증 대상이 된다. 세 메서드 모두 실구현됐다 —
 * 조회 두 건은 S1, {@link #updateGuestPassword} 는 S2.
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

    /**
     * 조회 비밀번호 해시를 교체한다. 회원 티켓이면 바꾸지 않는다 —
     * 판정은 {@link Ticket#changeGuestPasswordHash} 가 하고 그 주석에 이유가 있다.
     *
     * <p>클래스에 {@code @Transactional(readOnly = true)} 가 걸려 있어 이 메서드에만 쓰기
     * 트랜잭션을 다시 지정한다. 빠뜨리면 변경이 플러시되지 않고 <b>조용히 사라진다</b> — 호출자는
     * void 를 받으므로 실패를 알 방법이 없다.
     *
     * <p>조회 두 건과 마찬가지로 없는 티켓에 예외를 던지지 않는다. 호출자는 결과와 무관하게 같은
     * 응답을 주어야 하므로 예외가 올라가면 응답이 갈라져 티켓 존재 여부가 드러난다.
     */
    @Override
    @Transactional
    public void updateGuestPassword(Long ticketId, String passwordHash) {
        if (ticketId == null || passwordHash == null) {
            return;
        }
        boolean applied = ticketRepository.findById(ticketId)
                .map(ticket -> ticket.changeGuestPasswordHash(passwordHash))
                .orElse(false);
        // 해시는 로그에 남기지 않는다 — 자격증명이다 (docs/10 §3.3)
        log.debug("[guest] updateGuestPassword ticketId={} applied={}", ticketId, applied);
    }
}
