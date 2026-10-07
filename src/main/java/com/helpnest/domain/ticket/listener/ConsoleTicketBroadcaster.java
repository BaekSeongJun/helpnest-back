// @owner PMJ
package com.helpnest.domain.ticket.listener;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.helpnest.domain.ticket.dto.ConsoleTicketEvent;
import com.helpnest.domain.ticket.event.TicketAssignedEvent;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 접수·상태 변경·배정을 콘솔 목록에 알린다 ({@code /topic/console/tickets}, docs/02 §6).
 *
 * <h2>AFTER_COMMIT 인 이유</h2>
 * 커밋 전에 보내면 화면이 신호를 받고 목록을 다시 불러와도 아직 반영 전이라 옛 목록이 보인다.
 * 롤백된 변경은 아예 알리지 않는다.
 *
 * <h2>@Async 를 붙이지 않는 이유</h2>
 * 하는 일이 행 1개 조회 + 브로커 메모리 큐 적재뿐이라 요청 스레드에서 끝내도 짧다.
 * {@code NotificationListener} 처럼 저장·메일이 있는 구독자와 다르다.
 *
 * <p>ponytail: AI 분류로 우선순위만 바뀌면 발행할 이벤트가 없어 신호가 가지 않는다. 다음 신호 때
 * 목록을 다시 불러오며 맞춰진다. 즉시 반영이 필요해지면 분류 반영 시점에 이벤트를 하나 추가한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleTicketBroadcaster {

    static final String DESTINATION = "/topic/console/tickets";

    private final TicketRepository ticketRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(TicketCreatedEvent event) {
        broadcast(event.ticketId(), "CREATED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(TicketStatusChangedEvent event) {
        broadcast(event.ticketId(), "UPDATED");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssigned(TicketAssignedEvent event) {
        broadcast(event.ticketId(), "UPDATED");
    }

    /**
     * 발송 실패를 던지지 않는다 — 업무 트랜잭션은 이미 커밋됐고, 신호를 놓친 화면은 다음 신호나
     * 새로고침 때 맞춰진다. 이벤트는 같은 요청 스레드에서 돌기 때문에 여기서 던지면 이미 성공한
     * 요청이 실패 응답으로 바뀐다.
     */
    private void broadcast(Long ticketId, String type) {
        try {
            ticketRepository.findById(ticketId).ifPresent(t -> messagingTemplate.convertAndSend(DESTINATION,
                    new ConsoleTicketEvent(t.getId(), type, t.getStatus(), t.getPriority(), t.getAgentId())));
        } catch (RuntimeException e) {
            log.warn("[console] 목록 신호 발송 실패 ticketId={} event={} cause={}", ticketId, type, e.toString());
        }
    }
}
