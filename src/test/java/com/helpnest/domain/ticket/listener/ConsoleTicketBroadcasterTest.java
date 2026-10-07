// @owner PMJ
package com.helpnest.domain.ticket.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.helpnest.domain.ticket.dto.ConsoleTicketEvent;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketAssignedEvent;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 신호 내용과 실패 처리. "커밋 후에만 보낸다"는 {@code ConsoleTicketBroadcasterTxTest} 가 본다.
 */
@DisplayName("ConsoleTicketBroadcaster — 콘솔 목록 신호")
class ConsoleTicketBroadcasterTest {

    private final TicketRepository repository = mock(TicketRepository.class);
    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final ConsoleTicketBroadcaster broadcaster = new ConsoleTicketBroadcaster(repository, template);

    private Ticket assignedTicket() {
        Ticket ticket = Ticket.builder()
                .ticketNo("HN-20261007-000001").customerId(1L).title("t").content("c")
                .channel(TicketChannel.WEB).firstResponseDueAt(OffsetDateTime.now()).build();
        ReflectionTestUtils.setField(ticket, "id", 5L);
        ticket.assignTo(3L, OffsetDateTime.now());
        when(repository.findById(5L)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    @Test
    @DisplayName("접수는 CREATED, 상태 변경·배정은 UPDATED — 현재 상태·우선순위·담당자를 싣는다")
    void 신호_내용() {
        assignedTicket();

        broadcaster.onCreated(new TicketCreatedEvent(5L));
        broadcaster.onStatusChanged(new TicketStatusChangedEvent(5L, TicketStatus.RECEIVED, TicketStatus.ASSIGNED, null));
        broadcaster.onAssigned(new TicketAssignedEvent(5L, 3L));

        verify(template).convertAndSend(ConsoleTicketBroadcaster.DESTINATION,
                new ConsoleTicketEvent(5L, "CREATED", TicketStatus.ASSIGNED, TicketPriority.NORMAL, 3L));
        verify(template, times(2)).convertAndSend(ConsoleTicketBroadcaster.DESTINATION,
                new ConsoleTicketEvent(5L, "UPDATED", TicketStatus.ASSIGNED, TicketPriority.NORMAL, 3L));
    }

    @Test
    @DisplayName("티켓이 없으면 보내지 않고, 발송 실패는 호출자에게 던지지 않는다")
    void 없거나_실패() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        broadcaster.onCreated(new TicketCreatedEvent(9L));
        verify(template, never()).convertAndSend(anyString(), any(Object.class));

        assignedTicket();
        doThrow(new MessagingException("broker down")).when(template).convertAndSend(anyString(), any(Object.class));
        assertThatCode(() -> broadcaster.onCreated(new TicketCreatedEvent(5L))).doesNotThrowAnyException();
    }
}
