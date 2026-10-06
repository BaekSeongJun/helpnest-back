// @owner PMJ
package com.helpnest.domain.ticket.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.error.BusinessException;

/**
 * 72시간 자동 종료 스케줄러 단위 테스트.
 *
 * <p><b>가장 중요한 테스트는 {@link #keepsGoingAfterOneFailure()} 다.</b> 재문의로 상태가 바뀐
 * 티켓 한 건에서 멈추면 그 뒤의 티켓은 다음 회차에도 같은 자리에서 막혀 영영 종료되지 않는다.
 */
@DisplayName("AutoCloseScheduler — 해결 후 72시간 경과 자동 종료")
class AutoCloseSchedulerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T10:00:00Z");

    TicketRepository ticketRepository = mock(TicketRepository.class);
    TicketService ticketService = mock(TicketService.class);
    AutoCloseScheduler scheduler = new AutoCloseScheduler(ticketRepository, ticketService,
            Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("대상 조회 기준은 지금으로부터 72시간 전이다")
    void cutoffIs72HoursAgo() {
        when(ticketRepository.findAutoCloseTargets(any())).thenReturn(List.of());

        scheduler.scan();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(ticketRepository).findAutoCloseTargets(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(NOW.minusHours(72));
    }

    @Test
    @DisplayName("대상마다 SYSTEM 수행자로 CLOSED 전이를 요청한다")
    void closesEachTargetAsSystem() {
        when(ticketRepository.findAutoCloseTargets(any())).thenReturn(List.of(10L, 11L));

        scheduler.scan();

        verify(ticketService).changeStatus(eq(10L), eq(TicketStatus.CLOSED), anyString(), isNull(),
                eq(ActorRole.SYSTEM));
        verify(ticketService).changeStatus(eq(11L), eq(TicketStatus.CLOSED), anyString(), isNull(),
                eq(ActorRole.SYSTEM));
    }

    @Test
    @DisplayName("한 건이 전이 거부돼도 나머지는 같은 회차에 종료된다 — 재문의된 티켓에서 멈추면 안 된다")
    void keepsGoingAfterOneFailure() {
        when(ticketRepository.findAutoCloseTargets(any())).thenReturn(List.of(10L, 11L));
        doThrow(new BusinessException(TicketErrorCode.INVALID_TRANSITION))
                .when(ticketService).changeStatus(eq(10L), any(), anyString(), any(), any());

        assertThatCode(scheduler::scan).doesNotThrowAnyException();

        verify(ticketService).changeStatus(eq(11L), eq(TicketStatus.CLOSED), anyString(), isNull(),
                eq(ActorRole.SYSTEM));
    }

    @Test
    @DisplayName("대상이 없으면 아무것도 전이하지 않는다")
    void noTargetsNoTransition() {
        when(ticketRepository.findAutoCloseTargets(any())).thenReturn(List.of());

        scheduler.scan();

        verifyNoInteractions(ticketService);
    }

    @Test
    @DisplayName("이력 메모에 자동 종료 사유가 남는다 — 왜 종료됐는지 이력에서 읽혀야 한다")
    void recordsReasonInMemo() {
        when(ticketRepository.findAutoCloseTargets(any())).thenReturn(List.of(10L));

        scheduler.scan();

        ArgumentCaptor<String> memo = ArgumentCaptor.forClass(String.class);
        verify(ticketService).changeStatus(anyLong(), any(), memo.capture(), any(), any());
        assertThat(memo.getValue()).contains("72시간");
    }
}
