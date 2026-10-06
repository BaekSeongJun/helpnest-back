// @owner BSJ
package com.helpnest.domain.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.helpnest.domain.member.port.MemberInfo;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.listener.SurveyListener;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.port.ResolvedMailInfo;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.infra.mail.MailSender;
import com.helpnest.infra.mail.ResolvedMailCommand;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 리스너는 분기와 메일 재료 조립만 한다. 포트·서비스는 모킹하고 어느 길로 갔는지만 본다 */
@DisplayName("SurveyListener — 상태 변경 분기")
class SurveyListenerTest {

    private static final long TICKET_ID = 10L;

    SurveyService surveyService = mock(SurveyService.class);
    TicketQueryPort ticketQueryPort = mock(TicketQueryPort.class);
    MemberQueryPort memberQueryPort = mock(MemberQueryPort.class);
    MailSender mailSender = mock(MailSender.class);
    SurveyListener listener = new SurveyListener(surveyService, ticketQueryPort, memberQueryPort, mailSender,
            "http://localhost:3000");

    private void givenSurvey() {
        given(surveyService.issueOrReissue(TICKET_ID))
                .willReturn(new Survey(TICKET_ID, "tok-abc", OffsetDateTime.now()));
    }

    private ResolvedMailCommand sentMail() {
        ArgumentCaptor<ResolvedMailCommand> captor = ArgumentCaptor.forClass(ResolvedMailCommand.class);
        verify(mailSender).sendResolvedMail(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("비회원 티켓이 해결되면 티켓의 이름·이메일로 설문 링크 메일을 보낸다")
    void resolvedGuest() {
        givenSurvey();
        given(ticketQueryPort.getResolvedMailInfo(TICKET_ID)).willReturn(
                new ResolvedMailInfo("HN-1", "제목", null, "김비회원", "guest@example.com", "최종 답변"));

        listener.on(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, 3L));

        ResolvedMailCommand mail = sentMail();
        assertThat(mail.customerName()).isEqualTo("김비회원");
        assertThat(mail.email()).isEqualTo("guest@example.com");
        assertThat(mail.ticketNo()).isEqualTo("HN-1");
        assertThat(mail.finalReply()).isEqualTo("최종 답변");
        assertThat(mail.surveyUrl()).isEqualTo("http://localhost:3000/survey/tok-abc");
        verifyNoInteractions(memberQueryPort);
    }

    @Test
    @DisplayName("회원 티켓이면 회원 포트의 이름·이메일을 쓴다")
    void resolvedMember() {
        givenSurvey();
        given(ticketQueryPort.getResolvedMailInfo(TICKET_ID)).willReturn(
                new ResolvedMailInfo("HN-2", "제목", 7L, null, null, null));
        given(memberQueryPort.getMember(7L)).willReturn(
                new MemberInfo(7L, "member@example.com", "홍회원", "CUSTOMER", false, null));

        listener.on(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, null));

        ResolvedMailCommand mail = sentMail();
        assertThat(mail.customerName()).isEqualTo("홍회원");
        assertThat(mail.email()).isEqualTo("member@example.com");
        assertThat(mail.finalReply()).isNull();
    }

    @Test
    @DisplayName("해결 → 진행중(재문의)이면 설문을 만료하고 메일은 보내지 않는다")
    void reopened() {
        listener.on(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS, 5L));

        verify(surveyService).expire(TICKET_ID);
        verify(surveyService, never()).issueOrReissue(any());
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("관련 없는 전이는 아무것도 하지 않는다")
    void ignoresOthers() {
        listener.on(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.RECEIVED, TicketStatus.ASSIGNED, 5L));
        listener.on(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, 5L));

        verifyNoInteractions(surveyService, mailSender);
    }

    @Test
    @DisplayName("메일 재료 조회가 실패해도 예외를 밖으로 던지지 않는다")
    void swallowsFailure() {
        givenSurvey();
        given(ticketQueryPort.getResolvedMailInfo(TICKET_ID)).willThrow(new IllegalStateException("조회 실패"));

        assertThatCode(() -> listener.on(
                new TicketStatusChangedEvent(TICKET_ID, TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, 3L)))
                .doesNotThrowAnyException();
        verifyNoInteractions(mailSender);
    }
}
