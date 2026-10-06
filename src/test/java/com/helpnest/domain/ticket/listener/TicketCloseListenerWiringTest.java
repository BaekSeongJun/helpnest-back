// @owner PMJ
package com.helpnest.domain.ticket.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.helpnest.domain.survey.event.SurveySubmittedEvent;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * TicketCloseListener 구독 와이어링 — 설문 제출 이벤트가 실제로 리스너까지 도달해 티켓이 CLOSED 가
 * 되는지 본다. 구조와 주의사항은 {@code SurveyListenerWiringTest}(백성준) 를 그대로 따른다.
 *
 * <p><b>이 테스트가 막는 사고가 실제로 있었다.</b> {@code SurveySubmittedEvent} 는 발행만 되고
 * 구독자가 없는 상태로 머지돼 있었다 — 컴파일도 통과하고 설문 제출 API 도 200 을 돌려주지만
 * 티켓은 영원히 RESOLVED 에 남았다. {@code TicketCloseListenerTest} 처럼 {@code on()} 을 직접
 * 부르는 단위 테스트는 {@code @Component}·{@code @TransactionalEventListener} 가 빠져도 통과하므로
 * 이 사고를 잡지 못한다.
 *
 * <p>비회원 티켓을 쓰는 이유도 같은 문서의 설명과 같다 — 회원·담당자가 있으면
 * {@code NotificationListener} 가 같은 전이로 notification 행을 만들어 ticket 삭제를 막는다.
 */
@SpringBootTest
@DisplayName("TicketCloseListener — 설문 제출 이벤트 구독 와이어링")
class TicketCloseListenerWiringTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String GUEST_EMAIL = "close-wiring-guest@helpnest.local";

    @Autowired
    ApplicationEventPublisher eventPublisher;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long ticketId;

    /** 커밋된 행은 롤백되지 않는다. FK 순서대로 이력 → 티켓 (ticket 을 남기면 티켓 목록 테스트가 깨진다) */
    @AfterEach
    void cleanUp() {
        if (ticketId != null) {
            jdbcTemplate.update("delete from ticket_history where ticket_id = ?", ticketId);
            ticketRepository.deleteById(ticketId);
        }
    }

    @Test
    @DisplayName("설문 제출 커밋 후 티켓이 CLOSED 가 되고 시스템 수행자로 이력이 남는다")
    void closesTicketOnSurveySubmitted() {
        transactionTemplate.executeWithoutResult(status -> {
            Ticket ticket = ticketRepository.save(Ticket.builder()
                    .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                    .guestName("비회원")
                    .guestEmail(GUEST_EMAIL)
                    .guestPasswordHash("x")
                    .title("배송 문의")
                    .content("아직 안 왔어요")
                    .channel(TicketChannel.WEB)
                    .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                    .build());
            // 전이표상 CLOSED 로 갈 수 있는 상태는 RESOLVED 뿐이다
            ticket.changeStatusTo(TicketStatus.RESOLVED, OffsetDateTime.now());
            ticketId = ticket.getId();
            eventPublisher.publishEvent(new SurveySubmittedEvent(ticketId, 5));
        });

        await().atMost(TIMEOUT).until(() -> ticketRepository.findById(ticketId)
                .orElseThrow().getStatus() == TicketStatus.CLOSED);

        assertThat(ticketRepository.findById(ticketId).orElseThrow().getClosedAt()).isNotNull();

        // 이력이 없으면 "왜 종료됐는지"가 사라진다 — 수행자를 특정할 수 없으므로 SYSTEM 이다
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "select actor_type, actor_id, from_value, to_value from ticket_history"
                        + " where ticket_id = ? and action = 'STATUS_CHANGE'", ticketId);
        assertThat(history).containsEntry("actor_type", "SYSTEM")
                .containsEntry("from_value", "RESOLVED")
                .containsEntry("to_value", "CLOSED");
        assertThat(history.get("actor_id")).isNull();
    }
}
