// @owner PMJ
package com.helpnest.domain.sla.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.helpnest.domain.assignment.repository.LeadLookupRepository;
import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.repository.TicketSpecs;

/**
 * SLA 감시 스케줄러 단위 테스트.
 *
 * <p>DB 대신 mock 을 쓰는 이유는 검증 대상이 "누가 무엇을 받고 플래그가 어떻게 켜지는가"라는
 * 분기 규칙이기 때문이다. 조회 조건(JPQL) 자체는 쿼리 파라미터를 검증해 간접 확인한다.
 *
 * <p><b>가장 중요한 테스트는 {@link #warningUsesSharedCutoff()} 다.</b> 임박 기준이 콘솔 목록과
 * 어긋나면 "목록엔 임박 배지인데 알림은 안 오는" 상태가 되고, 이건 사용자가 버그로 신고하기
 * 전까지 아무도 모른다.
 */
@DisplayName("SlaScheduler — 첫 응답 SLA 임박·초과 감시")
class SlaSchedulerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T10:00:00Z");
    private static final long TICKET_ID = 10L;
    private static final long AGENT_ID = 3L;
    private static final long LEAD_ID = 1L;
    private static final String TICKET_NO = "HN-20261006-000001";

    /** NORMAL 1440분, 임박 비율 0.80 → 임박까지 1152분 */
    private static final SlaPolicy NORMAL = SlaPolicy.builder()
            .priority(TicketPriority.NORMAL)
            .responseMinutes(1440)
            .warningRatio(new BigDecimal("0.80"))
            .build();

    TicketRepository ticketRepository = mock(TicketRepository.class);
    SlaPolicyRepository slaPolicyRepository = mock(SlaPolicyRepository.class);
    LeadLookupRepository leadLookupRepository = mock(LeadLookupRepository.class);
    NotificationPort notificationPort = mock(NotificationPort.class);
    SlaScheduler scheduler = new SlaScheduler(ticketRepository, slaPolicyRepository, leadLookupRepository,
            notificationPort, Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC));

    private Ticket ticket(Long agentId) {
        Ticket ticket = Ticket.builder()
                .ticketNo(TICKET_NO)
                .title("배송 문의")
                .content("아직 안 왔어요")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(NOW.plusHours(4))
                .build();
        // id 는 영속화로만 채워지므로 단위 테스트에서는 직접 넣는다(NotificationListenerTest 와 같은 방식)
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        if (agentId != null) {
            ticket.assignTo(agentId, NOW);
        }
        return ticket;
    }

    private void givenNoBreach() {
        when(ticketRepository.findSlaBreachTargets(any(), any())).thenReturn(List.of());
    }

    private void givenNoWarning() {
        when(slaPolicyRepository.findAll()).thenReturn(List.of(NORMAL));
        when(ticketRepository.findSlaWarningTargets(any(), any(), any(), any())).thenReturn(List.of());
    }

    private void givenWarningTarget(Ticket ticket) {
        when(slaPolicyRepository.findAll()).thenReturn(List.of(NORMAL));
        when(ticketRepository.findSlaWarningTargets(eq(TicketPriority.NORMAL), any(), any(), any()))
                .thenReturn(List.of(ticket));
    }

    @Test
    @DisplayName("기한 초과: 담당 상담원과 팀장 모두에게 알리고 sla_breached 를 켠다")
    void breachNotifiesAgentAndLeads() {
        Ticket ticket = ticket(AGENT_ID);
        when(ticketRepository.findSlaBreachTargets(any(), any())).thenReturn(List.of(ticket));
        when(leadLookupRepository.findLeadMemberIds()).thenReturn(List.of(LEAD_ID));
        givenNoWarning();

        scheduler.scan();

        assertThat(ticket.isSlaBreached()).isTrue();
        verify(notificationPort).notify(eq(AGENT_ID), eq("SLA_BREACHED"), eq(TICKET_ID), anyString());
        verify(notificationPort).notify(eq(LEAD_ID), eq("SLA_BREACHED"), eq(TICKET_ID), anyString());
    }

    @Test
    @DisplayName("담당 상담원이 팀장을 겸해도 초과 알림은 한 통만 간다")
    void breachDoesNotDuplicateForLeadAgent() {
        when(ticketRepository.findSlaBreachTargets(any(), any())).thenReturn(List.of(ticket(LEAD_ID)));
        when(leadLookupRepository.findLeadMemberIds()).thenReturn(List.of(LEAD_ID));
        givenNoWarning();

        scheduler.scan();

        verify(notificationPort).notify(eq(LEAD_ID), eq("SLA_BREACHED"), eq(TICKET_ID), anyString());
    }

    @Test
    @DisplayName("기한 임박: 담당 상담원에게만 알리고 sla_warned 를 켠다")
    void warningNotifiesAgent() {
        Ticket ticket = ticket(AGENT_ID);
        givenNoBreach();
        givenWarningTarget(ticket);
        when(leadLookupRepository.findLeadMemberIds()).thenReturn(List.of(LEAD_ID));

        scheduler.scan();

        assertThat(ticket.isSlaWarned()).isTrue();
        verify(notificationPort).notify(eq(AGENT_ID), eq("SLA_WARNING"), eq(TICKET_ID), anyString());
        verify(notificationPort, never()).notify(eq(LEAD_ID), eq("SLA_WARNING"), anyLong(), anyString());
    }

    @Test
    @DisplayName("미배정 티켓의 임박은 팀장에게 간다 — 받을 사람이 없다고 건너뛰면 영영 알리지 못한다")
    void warningFallsBackToLeadsWhenUnassigned() {
        Ticket ticket = ticket(null);
        givenNoBreach();
        givenWarningTarget(ticket);
        when(leadLookupRepository.findLeadMemberIds()).thenReturn(List.of(LEAD_ID));

        scheduler.scan();

        assertThat(ticket.isSlaWarned()).isTrue();
        verify(notificationPort).notify(eq(LEAD_ID), eq("SLA_WARNING"), eq(TICKET_ID), anyString());
    }

    @Test
    @DisplayName("임박 조회 기준은 TicketSpecs.warningCutoff 와 같아야 한다 — 목록 배지와 알림이 어긋나면 안 된다")
    void warningUsesSharedCutoff() {
        givenNoBreach();
        givenNoWarning();

        scheduler.scan();

        // NORMAL 1440분 × 0.80 = 1152분 전에 접수된 티켓이 "지금 임박"이다
        verify(ticketRepository).findSlaWarningTargets(eq(TicketPriority.NORMAL),
                eq(TicketSpecs.warningCutoff(NORMAL, NOW)), eq(NOW), any());
        assertThat(TicketSpecs.warningCutoff(NORMAL, NOW)).isEqualTo(NOW.minusMinutes(1152));
    }

    @Test
    @DisplayName("해결·종료된 티켓은 감시 대상에서 빠진다 — 두 조회 모두 같은 상태 집합을 제외한다")
    void excludesDoneStatuses() {
        givenNoBreach();
        givenNoWarning();

        scheduler.scan();

        assertThat(capturedDoneStatuses())
                .containsExactlyInAnyOrder(TicketStatus.RESOLVED, TicketStatus.CLOSED);
    }

    @Test
    @DisplayName("대상이 없으면 아무에게도 알리지 않는다")
    void noTargetsNoNotification() {
        givenNoBreach();
        givenNoWarning();

        scheduler.scan();

        verifyNoInteractions(notificationPort);
    }

    @SuppressWarnings("unchecked")
    private Collection<TicketStatus> capturedDoneStatuses() {
        org.mockito.ArgumentCaptor<Collection<TicketStatus>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(ticketRepository).findSlaBreachTargets(any(), captor.capture());
        return captor.getValue();
    }
}
