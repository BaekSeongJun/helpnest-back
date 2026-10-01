// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.helpnest.domain.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;

/**
 * 고객에게 보여 주는 티켓번호 {@code HN-YYYYMMDD-NNNNNN} 생성기 (FR-INQ-03).
 *
 * <h2>일련번호를 DB 시퀀스에서 받는 이유</h2>
 * {@code MAX(ticket_no) + 1} 방식은 두 건이 동시에 접수되면 같은 번호를 읽어 가므로
 * {@code ticket_no} UNIQUE 제약에 걸린다. 시퀀스는 트랜잭션과 무관하게 증가해 경합이 없다
 * (롤백돼도 번호를 되돌리지 않으므로 번호에 구멍이 생길 수 있는데, 티켓번호는 연속성을
 * 보장하지 않는 식별자라 문제가 되지 않는다).
 *
 * <h2>날짜 부분은 Asia/Seoul 기준</h2>
 * 서버·DB 시간대와 무관하게 고객이 보는 날짜로 찍어야 하므로 KST 로 변환한 뒤 포맷한다
 * (docs/10 §1 "서버·DB·화면 모두 Asia/Seoul 기준 표시").
 *
 * <h2>6자리를 넘으면 자르지 않는다</h2>
 * 일련번호가 999,999 를 넘으면 7자리가 되어 번호가 하루 안에서 길어지지만 자르지 않는다.
 * {@code % 1000000} 로 접으면 같은 날 두 티켓이 같은 번호를 받아 UNIQUE 제약 위반이 되기
 * 때문이다. docs/03 §3.2 의 {@code lpad(nextval('ticket_no_seq')::text, 6, '0')} 주석도
 * 같은 방식이며, 8자리(1억 건)까지 VARCHAR(20) 안에 들어온다.
 */
@Component
@RequiredArgsConstructor
public class TicketNoGenerator {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final TicketRepository ticketRepository;

    /**
     * @param now 접수 시각. 현재 시각을 내부에서 읽지 않는 이유는 테스트에서 날짜 경계를
     *            고정해야 하고, 티켓 저장 시각과 번호의 날짜가 어긋나면 안 되기 때문이다.
     * @return 예 {@code HN-20261002-000123}
     */
    public String generate(OffsetDateTime now) {
        return format(now, ticketRepository.nextTicketNoSeq());
    }

    /** 순수 함수로 분리해 DB 없이 형식·날짜 경계를 검증할 수 있게 한다. */
    static String format(OffsetDateTime now, long sequence) {
        String day = DateTimeFormatter.BASIC_ISO_DATE.format(now.atZoneSameInstant(SEOUL).toLocalDate());
        return "HN-%s-%06d".formatted(day, sequence);
    }
}
