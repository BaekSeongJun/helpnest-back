// @owner PMJ
package com.helpnest.domain.sla.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;

import jakarta.persistence.EntityManager;

/**
 * 실제 PostgreSQL 에서 SLA 감시 대상 조회가 <b>어떤 행을 고르는지</b> 확인한다.
 *
 * <p>{@code SlaSchedulerTest} 는 리포지터리를 mock 으로 두므로 "누구에게 알리는가"만 검증하고
 * 조회 조건 자체는 보지 못한다. 부등호 방향이나 플래그 조건이 하나 틀어지면 알림이 조용히
 * 누락되거나 이미 알린 티켓에 또 가는데, 둘 다 로그만 봐서는 드러나지 않는다.
 *
 * <p>{@code created_at} 은 {@code @CreationTimestamp} 라 코드로 넣을 수 없어 저장 후 UPDATE 로
 * 과거로 민다 — 임박 판정이 접수 시각 기준이라 과거 티켓 없이는 검증할 수 없다. UPDATE 는
 * 영속성 컨텍스트를 거치지 않으므로 {@code clear()} 로 1차 캐시를 비워야 조회가 DB 를 다시 읽는다.
 *
 * <p>{@code @DataJpaTest} 가 아니라 {@code @SpringBootTest} 인 이유는 커넥션 때문이다 — 슬라이스
 * 테스트는 설정이 달라 제 컨텍스트와 Hikari 풀을 따로 들고, 그 풀은 컨텍스트 캐시가 살아 있는 동안
 * 닫히지 않는다. 이 클래스를 {@code @DataJpaTest} 로 두었더니 PostgreSQL 커넥션 슬롯이 바닥나
 * {@code DefaultMailSenderDbTest} 가 기동하지 못했다. 다수가 쓰는 공유 컨텍스트에 붙으면 풀이 늘지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("SLA 감시 대상 조회 — 실제 DB")
class SlaScanQueryDbTest {

    private static final Set<TicketStatus> DONE = EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T10:00:00Z");
    /** NORMAL 1440분 × 0.80 — {@code TicketSpecs.warningCutoff} 가 내놓는 값과 같다 */
    private static final OffsetDateTime WARNING_CUTOFF = NOW.minusMinutes(1152);

    /** 티켓번호 UNIQUE 제약 때문에 행마다 다른 번호가 필요하다 */
    private final AtomicInteger seq = new AtomicInteger();

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("위반: 기한 지난 미응답만 잡히고 응답 완료·이미 위반·종료·기한 내 건은 빠진다")
    void breachTargets() {
        Ticket overdue = given(NOW.minusHours(1), NOW.minusDays(2));
        given(NOW.minusHours(1), NOW.minusDays(2), t -> t.markFirstResponded(NOW.minusHours(2)));
        given(NOW.minusHours(1), NOW.minusDays(2), Ticket::markSlaBreached);
        given(NOW.minusHours(1), NOW.minusDays(2), t -> t.changeStatusTo(TicketStatus.CLOSED, NOW));
        given(NOW.plusHours(1), NOW.minusDays(2));

        assertThat(ticketRepository.findSlaBreachTargets(NOW, DONE))
                .extracting(Ticket::getId).containsExactly(overdue.getId());
    }

    @Test
    @DisplayName("임박: 임박 시각을 지난 미응답만 잡히고 기한을 이미 넘긴 건은 위반 쪽이 가져간다")
    void warningTargets() {
        Ticket warned = given(NOW.plusHours(4), WARNING_CUTOFF.minusMinutes(1));
        given(NOW.plusHours(4), WARNING_CUTOFF.plusMinutes(1));                      // 아직 임박 전
        given(NOW.minusHours(1), WARNING_CUTOFF.minusMinutes(1));                    // 기한 초과
        given(NOW.plusHours(4), WARNING_CUTOFF.minusMinutes(1), Ticket::markSlaWarned);

        assertThat(ticketRepository.findSlaWarningTargets(TicketPriority.NORMAL, WARNING_CUTOFF, NOW, DONE))
                .extracting(Ticket::getId).containsExactly(warned.getId());
    }

    @Test
    @DisplayName("임박 경계: 접수 시각이 기준과 정확히 같으면 포함된다 (<=)")
    void warningIncludesExactCutoff() {
        Ticket exact = given(NOW.plusHours(4), WARNING_CUTOFF);

        assertThat(ticketRepository.findSlaWarningTargets(TicketPriority.NORMAL, WARNING_CUTOFF, NOW, DONE))
                .extracting(Ticket::getId).containsExactly(exact.getId());
    }

    private Ticket given(OffsetDateTime dueAt, OffsetDateTime createdAt) {
        return given(dueAt, createdAt, t -> {
        });
    }

    /** 저장 후 {@code created_at} 을 원하는 과거 시각으로 밀고 1차 캐시를 비운다 */
    private Ticket given(OffsetDateTime dueAt, OffsetDateTime createdAt, Consumer<Ticket> setup) {
        // 비회원 티켓으로 만든다 — chk_ticket_customer 가 customer_id·guest_email 중 하나를 요구하는데
        // 회원을 쓰면 member FK 때문에 시드 계정에 묶인다. SLA 조회는 둘을 구분하지 않는다
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261006-%06d".formatted(seq.incrementAndGet()))
                .title("배송 문의")
                .content("아직 안 왔어요")
                .channel(TicketChannel.WEB)
                .guestName("김비회원")
                .guestEmail("guest@example.com")
                .firstResponseDueAt(dueAt)
                .build());
        setup.accept(ticket);
        em.flush();
        jdbcTemplate.update("update ticket set created_at = ? where ticket_id = ?", createdAt, ticket.getId());
        em.clear();
        return ticket;
    }
}
