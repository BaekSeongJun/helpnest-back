// @owner BSJ
package com.helpnest.domain.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.repository.SurveyRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SurveyListener 구독 와이어링 — 이벤트가 실제로 리스너까지 도달해 survey 행이 바뀌는지 본다.
 *
 * <p>{@code SurveyListenerTest} 는 {@code on()} 을 직접 호출해 분기만 검증하므로, {@code @Component} 나
 * {@code @TransactionalEventListener} 가 빠져도 통과한다. 여기서는 {@code @Transactional} 없이
 * {@link TransactionTemplate} 으로 실제 커밋을 일으켜 {@code AFTER_COMMIT} + {@code @Async} 경로를 탄다.
 * 커밋된 행은 롤백되지 않으니 {@link #cleanUp()} 에서 직접 지운다(ticket 을 남기면 티켓번호·목록 테스트가 깨진다).
 * 비회원 티켓을 써서 NotificationListener(박민재)가 같은 이벤트로 notification 행을 만들지 않게 한다
 * (회원이면 그 행이 ticket 삭제를 막는다). {@code @MockitoBean} 은 컨텍스트를 하나 더 만들어 DB 연결 슬롯을
 * 고갈시키므로 쓰지 않고, 실제 메일 발송이 남긴 mail_log 행으로 확인한다.
 */
@SpringBootTest
@DisplayName("SurveyListener — 커밋 후 비동기 구독 와이어링")
class SurveyListenerWiringTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    ApplicationEventPublisher eventPublisher;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    SurveyRepository surveyRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private static final String GUEST_EMAIL = "wiring-guest@helpnest.local";

    private Long ticketId;

    /** FK 순서대로 설문 → 티켓. mail_log 는 ticket_id 가 비어 있을 수 있어 수신자로 지운다 */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from mail_log where to_email = ?", GUEST_EMAIL);
        if (ticketId != null) {
            surveyRepository.findByTicketId(ticketId).ifPresent(surveyRepository::delete);
            ticketRepository.deleteById(ticketId);
        }
    }

    @Test
    @DisplayName("RESOLVED 커밋 후 설문이 만들어지고 결과 메일이 나가며, 재문의 커밋 후 설문이 만료된다")
    void issuesOnResolvedAndExpiresOnReopen() {
        transactionTemplate.executeWithoutResult(status -> {
            ticketId = ticketRepository.save(Ticket.builder()
                    .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                    .guestName("비회원")
                    .guestEmail(GUEST_EMAIL)
                    .guestPasswordHash("x")
                    .title("배송 문의")
                    .content("아직 안 왔어요")
                    .channel(TicketChannel.WEB)
                    .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                    .build()).getId();
            eventPublisher.publishEvent(new TicketStatusChangedEvent(ticketId, TicketStatus.IN_PROGRESS,
                    TicketStatus.RESOLVED, null));
        });

        await().atMost(TIMEOUT).until(() -> surveyRepository.findByTicketId(ticketId).isPresent());
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "select count(*) from mail_log where to_email = ?", Integer.class, GUEST_EMAIL) > 0);
        Survey issued = surveyRepository.findByTicketId(ticketId).orElseThrow();
        assertThat(issued.isExpired(OffsetDateTime.now())).isFalse();

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(
                new TicketStatusChangedEvent(ticketId, TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS, null)));

        await().atMost(TIMEOUT).until(() ->
                surveyRepository.findByTicketId(ticketId).orElseThrow().isExpired(OffsetDateTime.now()));
    }
}
