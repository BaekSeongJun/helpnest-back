// @owner PMJ
package com.helpnest.domain.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 실제 PostgreSQL 의 ticket_no_seq 로 번호가 증가하는지 확인한다.
 * 시퀀스는 트랜잭션에 묶이지 않으므로 테스트가 롤백돼도 번호는 전진한다 — 의도된 동작이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TicketNoGenerator.class)
@DisplayName("TicketNoGenerator — DB 시퀀스 연동")
class TicketNoGeneratorDbTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-02T10:30:00+09:00");

    @Autowired
    private TicketNoGenerator generator;

    @Autowired
    private TicketRepository ticketRepository;

    @Test
    @DisplayName("연속 호출하면 일련번호가 커진다")
    void sequenceIncreases() {
        long first = ticketRepository.nextTicketNoSeq();
        long second = ticketRepository.nextTicketNoSeq();

        assertThat(second).isGreaterThan(first);
    }

    @Test
    @DisplayName("generate 는 같은 날 호출해도 서로 다른 번호를 만든다")
    void generatesUniqueNumbers() {
        String first = generator.generate(NOW);
        String second = generator.generate(NOW);

        assertThat(first).startsWith("HN-20261002-").isNotEqualTo(second);
        assertThat(second).startsWith("HN-20261002-");
    }
}
