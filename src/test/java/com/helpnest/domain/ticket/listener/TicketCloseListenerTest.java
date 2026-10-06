// @owner PMJ
package com.helpnest.domain.ticket.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.helpnest.domain.survey.event.SurveySubmittedEvent;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.error.BusinessException;

/**
 * 설문 제출 → CLOSED 전이 단위 테스트.
 *
 * <p>검증 대상은 "무엇을 들고 {@code changeStatus} 를 부르는가"다 — 전이 검증·이력·이벤트는
 * {@code TicketService} 의 책임이고 거기에 이미 테스트가 있다({@code TicketStatusApiTest}).
 * 그래서 서비스는 mock 으로 둔다.
 */
@DisplayName("TicketCloseListener — 설문 제출 시 티켓 종료")
class TicketCloseListenerTest {

    private static final long TICKET_ID = 10L;

    TicketService ticketService = mock(TicketService.class);
    TicketCloseListener listener = new TicketCloseListener(ticketService);

    @Test
    @DisplayName("설문 제출을 받으면 SYSTEM 수행자로 CLOSED 전이를 요청한다")
    void closesTicketAsSystem() {
        listener.on(new SurveySubmittedEvent(TICKET_ID, 5));

        // actorId 가 null 이어야 이력에 actor_type=SYSTEM 으로 남는다 (클래스 주석)
        verify(ticketService).changeStatus(eq(TICKET_ID), eq(TicketStatus.CLOSED), anyString(),
                isNull(), eq(ActorRole.SYSTEM));
    }

    @Test
    @DisplayName("이미 종료·재문의된 티켓이면 전이가 거부되지만 예외를 밖으로 던지지 않는다")
    void swallowsTransitionFailure() {
        doThrow(new BusinessException(TicketErrorCode.INVALID_TRANSITION))
                .when(ticketService).changeStatus(anyLong(), any(), anyString(), any(), any());

        // 던지면 @Async 스레드에서 사라질 뿐 설문 제출이 되돌아가지 않는다 — 자동 종료 스케줄러가 최종 보장
        assertThatCode(() -> listener.on(new SurveySubmittedEvent(TICKET_ID, 1)))
                .doesNotThrowAnyException();
    }
}
