// @owner PMJ
package com.helpnest.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.notification.entity.Notification;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.repository.NotificationRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.event.ReplyCreatedEvent;
import com.helpnest.domain.ticket.event.TicketAssignedEvent;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 구독 와이어링 통합 테스트 — 이벤트가 실제로 리스너까지 도달해 notification 행이 남는지 본다.
 *
 * <h2>왜 단위 테스트로는 부족한가</h2>
 * {@code NotificationListenerTest} 는 리스너 메서드를 직접 호출하므로 분기 규칙은 검증하지만
 * <b>스프링이 그 메서드를 구독자로 인식했는지는 검증하지 못한다</b>. {@code @Component} 가 빠지거나
 * {@code @TransactionalEventListener} 의 phase 를 잘못 쓰면 알림이 한 건도 가지 않는데 단위
 * 테스트는 전부 통과한다. 이 테스트는 그 공백만 메운다 — 분기 세부는 여기서 다시 보지 않는다.
 *
 * <h2>{@code @Transactional} 을 붙이지 않는다</h2>
 * 테스트 메서드를 트랜잭션으로 감싸면 커밋이 일어나지 않아 {@code AFTER_COMMIT} 리스너가 아예
 * 돌지 않는다(기존 API 테스트들이 이 리스너를 못 덮는 이유). 대신
 * {@link TransactionTemplate} 으로 실제 커밋을 일으키고, 롤백으로 지워지지 않는 행은
 * {@link #cleanUp()} 에서 직접 지운다 — <b>ticket 행을 남기면 티켓번호·목록 테스트가 함께
 * 깨진다</b>({@code TicketQueryPortTest} 주석과 같은 이유).
 *
 * <p>리스너가 {@code @Async} 라 커밋 직후에는 아직 행이 없다. 그래서 개수가 찰 때까지 기다린다.
 */
@SpringBootTest
@DisplayName("NotificationListener — 커밋 후 비동기 구독 와이어링")
class NotificationListenerWiringTest {

    /** 로컬 PostgreSQL + 스레드 풀(core 2)이면 수십 ms 로 끝난다. 여유를 둔 상한이다 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    ApplicationEventPublisher eventPublisher;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketReplyRepository ticketReplyRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    MemberRepository memberRepository;

    private Long agentId;
    private Long customerId;
    private Long ticketId;
    private Long replyId;

    @BeforeEach
    void setUp() {
        agentId = memberId("agent1@helpnest.local");
        customerId = memberId("customer1@helpnest.local");
    }

    /** 커밋된 행은 테스트가 실패해도 남으므로 반드시 지운다. FK 순서대로 알림 → 답변 → 티켓 */
    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll(notifications());
        if (replyId != null) {
            ticketReplyRepository.deleteById(replyId);
        }
        if (ticketId != null) {
            ticketRepository.deleteById(ticketId);
        }
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    /**
     * 이 테스트가 만든 티켓의 알림만 고른다. 다른 테스트가 남긴 행과 섞이지 않게 하려는 것이며,
     * 조회용 메서드를 Repository 에 새로 만들지 않기 위해 {@code findAll} 을 걸러 쓴다.
     */
    private List<Notification> notifications() {
        if (ticketId == null) {
            return List.of();
        }
        return notificationRepository.findAll().stream()
                .filter(n -> ticketId.equals(n.getTicketId()))
                .toList();
    }

    @Test
    @DisplayName("배정·답변·상태 변경 이벤트가 커밋 후 각 메서드로 전달되어 알림 4건이 저장된다")
    void deliversAfterCommit() {
        transactionTemplate.executeWithoutResult(status -> {
            Ticket ticket = ticketRepository.save(Ticket.builder()
                    .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                    .customerId(customerId)
                    .title("배송 문의")
                    .content("아직 안 왔어요")
                    .channel(TicketChannel.WEB)
                    .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                    .build());
            ticket.assignTo(agentId, OffsetDateTime.now());
            ticketId = ticket.getId();

            replyId = ticketReplyRepository.save(TicketReply.builder()
                    .ticketId(ticketId)
                    .writerId(customerId)
                    .writerType(WriterType.CUSTOMER)
                    .content("아직 답을 못 받았어요")
                    .isInternal(false)
                    .build()).getId();

            // 메일이 끼지 않는 세 경로만 고른다 — 와이어링 확인에 외부 전송까지 묶을 이유가 없다
            eventPublisher.publishEvent(new TicketAssignedEvent(ticketId, agentId));
            eventPublisher.publishEvent(
                    new ReplyCreatedEvent(ticketId, replyId, WriterType.CUSTOMER.name(), false));
            // actorId null = 시스템 자동 전이 → 고객·상담원 둘 다 받는다
            eventPublisher.publishEvent(new TicketStatusChangedEvent(ticketId, TicketStatus.ASSIGNED,
                    TicketStatus.IN_PROGRESS, null));
        });

        await().atMost(TIMEOUT).until(() -> notifications().size() >= 4);

        assertThat(notifications())
                .extracting(Notification::getType, Notification::getReceiverId)
                .containsExactlyInAnyOrder(
                        tuple(NotificationType.ASSIGNED, agentId),
                        tuple(NotificationType.CUSTOMER_REPLY, agentId),
                        tuple(NotificationType.STATUS_CHANGED, agentId),
                        tuple(NotificationType.STATUS_CHANGED, customerId));
    }
}
