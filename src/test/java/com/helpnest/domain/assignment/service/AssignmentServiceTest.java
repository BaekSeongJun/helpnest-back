// @owner PMJ
package com.helpnest.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.helpnest.domain.assignment.repository.LeadLookupRepository;
import com.helpnest.domain.member.port.MemberInfo;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketAssignedEvent;
import com.helpnest.domain.ticket.repository.AgentLoad;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 최소 부하 자동 배정 단위 테스트 (PRD 6.2, docs/10 §3.4 필수 항목 "배정").
 *
 * <p>DB 대신 mock 을 쓰는 이유: 검증 대상은 "후보 중 누구를 고르는가"라는 선택 규칙이다.
 * 부하·last_assigned_at 조합을 DB 에 만들어 넣으면 티켓 여러 건을 심어야 하고, 정작 선택
 * 규칙이 틀려도 데이터 준비 실수와 구분하기 어려워진다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssignmentService — 최소 부하 자동 배정")
class AssignmentServiceTest {

    private static final Long TICKET_ID = 10L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-02T10:00:00+09:00");

    @Mock
    TicketRepository ticketRepository;
    @Mock
    TicketHistoryRepository ticketHistoryRepository;
    @Mock
    LeadLookupRepository leadLookupRepository;
    @Mock
    MemberQueryPort memberQueryPort;
    @Mock
    NotificationPort notificationPort;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    AssignmentService assignmentService;

    private static Ticket receivedTicket() {
        return Ticket.builder()
                .ticketNo("HN-20261002-000001")
                .customerId(1L)
                .title("배송 문의")
                .content("아직 안 왔어요")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(NOW.plusMinutes(1440))
                .build();
    }

    private static MemberInfo agent(long id, OffsetDateTime lastAssignedAt) {
        return new MemberInfo(id, "agent%d@helpnest.local".formatted(id), "상담원" + id, "AGENT",
                true, lastAssignedAt);
    }

    private void givenTicket(Ticket ticket) {
        when(ticketRepository.findByIdForUpdate(TICKET_ID)).thenReturn(Optional.of(ticket));
    }

    private void givenCandidates(List<MemberInfo> agents, List<AgentLoad> loads) {
        when(memberQueryPort.findAssignableAgents()).thenReturn(agents);
        when(ticketRepository.countActiveByAgentIds(anyList())).thenReturn(loads);
    }

    @Nested
    @DisplayName("상담원 선택 규칙")
    class Selection {

        @Test
        @DisplayName("처리 중 티켓이 적은 상담원을 고른다 (2건 vs 1건 → 1건)")
        void picksLeastLoaded() {
            Ticket ticket = receivedTicket();
            givenTicket(ticket);
            givenCandidates(List.of(agent(1L, NOW.minusHours(5)), agent(2L, NOW.minusHours(5))),
                    List.of(new AgentLoad(1L, 2), new AgentLoad(2L, 1)));

            Long assigned = assignmentService.autoAssign(TICKET_ID);

            assertThat(assigned).isEqualTo(2L);
            assertThat(ticket.getAgentId()).isEqualTo(2L);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        }

        @Test
        @DisplayName("부하가 같으면 last_assigned_at 이 오래된 쪽을 고른다")
        void breaksTieByLastAssignedAt() {
            givenTicket(receivedTicket());
            givenCandidates(List.of(agent(1L, NOW.minusHours(1)), agent(2L, NOW.minusHours(9))),
                    List.of(new AgentLoad(1L, 3), new AgentLoad(2L, 3)));

            assertThat(assignmentService.autoAssign(TICKET_ID)).isEqualTo(2L);
        }

        @Test
        @DisplayName("한 번도 배정받지 않은 상담원(last_assigned_at null)이 가장 먼저다")
        void prefersNeverAssignedAgent() {
            givenTicket(receivedTicket());
            givenCandidates(List.of(agent(1L, NOW.minusDays(30)), agent(2L, null)),
                    List.of(new AgentLoad(1L, 1), new AgentLoad(2L, 1)));

            assertThat(assignmentService.autoAssign(TICKET_ID)).isEqualTo(2L);
        }

        @Test
        @DisplayName("집계에 없는 상담원은 부하 0 으로 본다 — group by 결과에 빠지기 때문")
        void treatsMissingLoadAsZero() {
            givenTicket(receivedTicket());
            givenCandidates(List.of(agent(1L, NOW.minusHours(9)), agent(2L, NOW.minusHours(1))),
                    List.of(new AgentLoad(1L, 4)));

            assertThat(assignmentService.autoAssign(TICKET_ID)).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("배정하지 않는 경우")
    class NoAssignment {

        @Test
        @DisplayName("가용 상담원이 없으면 RECEIVED 를 유지하고 팀장에게 UNASSIGNED 알림")
        void keepsReceivedAndNotifiesLeads() {
            Ticket ticket = receivedTicket();
            givenTicket(ticket);
            when(memberQueryPort.findAssignableAgents()).thenReturn(List.of());
            when(leadLookupRepository.findLeadMemberIds()).thenReturn(List.of(7L, 8L));

            Long assigned = assignmentService.autoAssign(TICKET_ID);

            assertThat(assigned).isNull();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
            assertThat(ticket.getAgentId()).isNull();
            verify(notificationPort).notify(eq(7L), eq("UNASSIGNED"), eq(TICKET_ID), any());
            verify(notificationPort).notify(eq(8L), eq("UNASSIGNED"), eq(TICKET_ID), any());
            verify(ticketHistoryRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("이미 배정된 티켓은 후보 조회조차 하지 않는다 (S3 FR-CHT-06 재배정 금지도 이 가드)")
        void skipsAlreadyAssignedTicket() {
            Ticket ticket = receivedTicket();
            ticket.assignTo(99L, NOW);
            givenTicket(ticket);

            Long assigned = assignmentService.autoAssign(TICKET_ID);

            assertThat(assigned).isNull();
            assertThat(ticket.getAgentId()).isEqualTo(99L);
            verifyNoInteractions(memberQueryPort, notificationPort, eventPublisher);
            verify(ticketHistoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("배정 시 함께 일어나는 일")
    class SideEffects {

        @Test
        @DisplayName("ASSIGN 이력(actorType=SYSTEM) + last_assigned_at 갱신 + TicketAssignedEvent")
        void writesHistoryTouchesAgentAndPublishesEvent() {
            givenTicket(receivedTicket());
            givenCandidates(List.of(agent(5L, null)), List.of());

            assignmentService.autoAssign(TICKET_ID);

            ArgumentCaptor<TicketHistory> history = ArgumentCaptor.forClass(TicketHistory.class);
            verify(ticketHistoryRepository).save(history.capture());
            assertThat(history.getValue().getAction()).isEqualTo(HistoryAction.ASSIGN);
            assertThat(history.getValue().getFromValue()).isNull();
            assertThat(history.getValue().getToValue()).isEqualTo("5");
            assertThat(history.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);
            assertThat(history.getValue().getActorId()).isNull();

            verify(memberQueryPort).touchLastAssigned(5L);
            verify(eventPublisher).publishEvent(new TicketAssignedEvent(null, 5L));
        }

        @Test
        @DisplayName("수동 재배정은 REASSIGN 이력에 이전 담당자를 남긴다")
        void reassignRecordsPreviousAgent() {
            Ticket ticket = receivedTicket();
            ticket.assignTo(3L, NOW);
            givenTicket(ticket);
            when(memberQueryPort.getMember(4L)).thenReturn(agent(4L, null));

            assignmentService.assignTo(TICKET_ID, 4L, "휴가 인수인계", 2L);

            ArgumentCaptor<TicketHistory> history = ArgumentCaptor.forClass(TicketHistory.class);
            verify(ticketHistoryRepository).save(history.capture());
            assertThat(history.getValue().getAction()).isEqualTo(HistoryAction.REASSIGN);
            assertThat(history.getValue().getFromValue()).isEqualTo("3");
            assertThat(history.getValue().getToValue()).isEqualTo("4");
            assertThat(history.getValue().getActorType()).isEqualTo(ActorType.MEMBER);
            assertThat(history.getValue().getActorId()).isEqualTo(2L);
            assertThat(history.getValue().getMemo()).isEqualTo("휴가 인수인계");
            assertThat(ticket.getAgentId()).isEqualTo(4L);
            verify(memberQueryPort, times(1)).touchLastAssigned(4L);
        }
    }
}
