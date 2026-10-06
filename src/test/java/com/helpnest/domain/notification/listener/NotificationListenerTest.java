// @owner PMJ
package com.helpnest.domain.notification.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.helpnest.domain.member.port.MemberInfo;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.notification.port.NotificationPort;
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
import com.helpnest.infra.mail.AgentReplyMailCommand;
import com.helpnest.infra.mail.MailSender;

/**
 * 이벤트별 알림 분기 단위 테스트.
 *
 * <p>DB 대신 mock 을 쓰는 이유는 검증 대상이 "어떤 이벤트에 누가 무엇을 받는가"라는 분기 규칙이기
 * 때문이다. 저장·실시간 발송은 {@code NotificationAdapterTest}·{@code NotificationPushTest} 가,
 * 메일 10분 묶음은 {@code DefaultMailSenderDbTest} 가 이미 각자 검증한다.
 *
 * <p><b>가장 중요한 테스트는 {@link #internalMemo()} 다.</b> 내부 메모가 새면 상담원끼리 주고받은
 * 메모가 그대로 고객 알림·메일로 나간다.
 */
@DisplayName("NotificationListener — 이벤트별 알림 분기")
class NotificationListenerTest {

    private static final long TICKET_ID = 10L;
    private static final long REPLY_ID = 77L;
    private static final long AGENT_ID = 3L;
    private static final long CUSTOMER_ID = 7L;
    private static final String TICKET_NO = "HN-20261006-000001";
    private static final String GUEST_EMAIL = "guest@example.com";
    private static final String MEMBER_EMAIL = "member@example.com";
    private static final String FRONT_ORIGIN = "http://localhost:3000";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-06T10:00:00+09:00");

    TicketRepository ticketRepository = mock(TicketRepository.class);
    TicketReplyRepository ticketReplyRepository = mock(TicketReplyRepository.class);
    MemberQueryPort memberQueryPort = mock(MemberQueryPort.class);
    NotificationPort notificationPort = mock(NotificationPort.class);
    MailSender mailSender = mock(MailSender.class);
    NotificationListener listener = new NotificationListener(ticketRepository, ticketReplyRepository,
            memberQueryPort, notificationPort, mailSender, FRONT_ORIGIN);

    /** 담당 상담원이 배정된 회원 티켓 */
    private Ticket givenMemberTicket() {
        Ticket ticket = baseBuilder().customerId(CUSTOMER_ID).build();
        return register(ticket);
    }

    /** 담당 상담원이 배정된 비회원 티켓 */
    private Ticket givenGuestTicket() {
        Ticket ticket = baseBuilder().guestName("김비회원").guestEmail(GUEST_EMAIL).build();
        return register(ticket);
    }

    private Ticket register(Ticket ticket) {
        // id 는 영속화로만 채워지므로 단위 테스트에서는 직접 넣는다(SurveyServiceTest 와 같은 방식)
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ticket.assignTo(AGENT_ID, NOW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        return ticket;
    }

    private Ticket.TicketBuilder baseBuilder() {
        return Ticket.builder()
                .ticketNo(TICKET_NO)
                .title("배송 문의")
                .content("아직 안 왔어요")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(NOW.plusDays(1));
    }

    private void givenReply(String content) {
        when(ticketReplyRepository.findById(REPLY_ID)).thenReturn(Optional.of(TicketReply.builder()
                .ticketId(TICKET_ID)
                .content(content)
                .writerType(WriterType.AGENT)
                .build()));
    }

    private ReplyCreatedEvent replyEvent(WriterType writerType, boolean internal) {
        return new ReplyCreatedEvent(TICKET_ID, REPLY_ID, writerType.name(), internal);
    }

    private String notifiedMessage(long receiverId, String type) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notificationPort).notify(eq(receiverId), eq(type), eq(TICKET_ID), captor.capture());
        return captor.getValue();
    }

    private AgentReplyMailCommand sentMail() {
        ArgumentCaptor<AgentReplyMailCommand> captor = ArgumentCaptor.forClass(AgentReplyMailCommand.class);
        verify(mailSender).sendAgentReplyMail(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("내부 메모는 알림도 메일도 보내지 않는다 — 티켓 조회조차 하지 않는다")
    void internalMemo() {
        listener.onReplyCreated(replyEvent(WriterType.AGENT, true));

        verifyNoInteractions(notificationPort, mailSender, ticketRepository, ticketReplyRepository);
    }

    @Test
    @DisplayName("고객 답글은 담당 상담원에게 CUSTOMER_REPLY 만 보낸다(메일 없음)")
    void customerReply() {
        givenMemberTicket();
        givenReply("아직 답을 못 받았어요");

        listener.onReplyCreated(replyEvent(WriterType.CUSTOMER, false));

        assertThat(notifiedMessage(AGENT_ID, "CUSTOMER_REPLY"))
                .contains(TICKET_NO, "아직 답을 못 받았어요");
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("비회원 답글도 담당 상담원에게 CUSTOMER_REPLY 로 간다")
    void guestReply() {
        givenGuestTicket();
        givenReply("저도 같은 문제예요");

        listener.onReplyCreated(replyEvent(WriterType.GUEST, false));

        verify(notificationPort).notify(eq(AGENT_ID), eq("CUSTOMER_REPLY"), eq(TICKET_ID), anyString());
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("상담원 공개 답변은 회원 고객에게 AGENT_REPLY 웹 알림과 메일을 함께 보낸다")
    void agentReplyToMember() {
        givenMemberTicket();
        givenReply("오늘 재발송 처리했습니다.");
        when(memberQueryPort.getMember(CUSTOMER_ID))
                .thenReturn(new MemberInfo(CUSTOMER_ID, MEMBER_EMAIL, "홍회원", "CUSTOMER", false, null));

        listener.onReplyCreated(replyEvent(WriterType.AGENT, false));

        assertThat(notifiedMessage(CUSTOMER_ID, "AGENT_REPLY")).contains(TICKET_NO, "재발송");
        AgentReplyMailCommand mail = sentMail();
        assertThat(mail.email()).isEqualTo(MEMBER_EMAIL);
        assertThat(mail.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(mail.replyPreview()).isEqualTo("오늘 재발송 처리했습니다.");
        assertThat(mail.ticketUrl()).isEqualTo(FRONT_ORIGIN + "/my/inquiries/" + TICKET_ID);
    }

    @Test
    @DisplayName("비회원 티켓은 웹 알림 없이 티켓의 이메일로만 보낸다")
    void agentReplyToGuest() {
        givenGuestTicket();
        givenReply("확인 후 안내드립니다.");

        listener.onReplyCreated(replyEvent(WriterType.AGENT, false));

        AgentReplyMailCommand mail = sentMail();
        assertThat(mail.email()).isEqualTo(GUEST_EMAIL);
        assertThat(mail.ticketUrl()).isEqualTo(FRONT_ORIGIN + "/inquiry/lookup");
        // 비회원은 notification.receiver_id 가 없어 웹 알림 자체가 성립하지 않는다
        verifyNoInteractions(notificationPort, memberQueryPort);
    }

    @Test
    @DisplayName("연속 공개 답변은 매번 MailSender 에 넘긴다 — 10분 묶음 판정은 MailSender 의 책임이다")
    void delegatesBundlingToMailSender() {
        givenGuestTicket();
        givenReply("추가 안내드립니다.");

        for (int i = 0; i < 3; i++) {
            listener.onReplyCreated(replyEvent(WriterType.AGENT, false));
        }

        // 여기서 억제하면 10분이 지난 뒤의 답변도 함께 삼켜진다(DefaultMailSenderDbTest 가 묶음을 검증)
        verify(mailSender, times(3)).sendAgentReplyMail(any());
    }

    @Test
    @DisplayName("미리보기는 100자까지만 넣고 줄바꿈을 한 줄로 접는다")
    void previewIsTrimmed() {
        givenGuestTicket();
        givenReply("첫 줄입니다.\n\n" + "가".repeat(200));

        listener.onReplyCreated(replyEvent(WriterType.AGENT, false));

        assertThat(sentMail().replyPreview())
                .hasSize(101)
                .startsWith("첫 줄입니다. 가")
                .endsWith("…");
    }

    @Test
    @DisplayName("답변을 찾지 못해도 예외를 밖으로 던지지 않는다")
    void missingReply() {
        givenMemberTicket();
        when(ticketReplyRepository.findById(REPLY_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.onReplyCreated(replyEvent(WriterType.AGENT, false)))
                .doesNotThrowAnyException();
        verifyNoInteractions(notificationPort, mailSender);
    }

    @Test
    @DisplayName("메일 발송이 실패해도 웹 알림은 이미 보냈고 예외는 올라오지 않는다")
    void mailFailureDoesNotBlock() {
        givenMemberTicket();
        givenReply("처리했습니다.");
        when(memberQueryPort.getMember(CUSTOMER_ID))
                .thenReturn(new MemberInfo(CUSTOMER_ID, MEMBER_EMAIL, "홍회원", "CUSTOMER", false, null));
        doThrow(new IllegalStateException("SES 장애")).when(mailSender).sendAgentReplyMail(any());

        assertThatCode(() -> listener.onReplyCreated(replyEvent(WriterType.AGENT, false)))
                .doesNotThrowAnyException();
        verify(notificationPort).notify(eq(CUSTOMER_ID), eq("AGENT_REPLY"), eq(TICKET_ID), anyString());
    }

    @Test
    @DisplayName("배정되면 담당 상담원에게 ASSIGNED 를 보낸다")
    void assigned() {
        givenMemberTicket();

        listener.onAssigned(new TicketAssignedEvent(TICKET_ID, AGENT_ID));

        assertThat(notifiedMessage(AGENT_ID, "ASSIGNED")).contains(TICKET_NO, "배송 문의");
    }

    @Test
    @DisplayName("시스템 자동 전이(actorId null)에서도 고객·상담원 모두에게 보낸다")
    void statusChangedBySystem() {
        givenMemberTicket();

        listener.onStatusChanged(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.RESOLVED,
                TicketStatus.CLOSED, null));

        assertThat(notifiedMessage(CUSTOMER_ID, "STATUS_CHANGED")).contains(TICKET_NO, "종료");
        verify(notificationPort).notify(eq(AGENT_ID), eq("STATUS_CHANGED"), eq(TICKET_ID), anyString());
    }

    @Test
    @DisplayName("상태를 바꾼 당사자에게는 보내지 않는다")
    void statusChangedExcludesActor() {
        givenMemberTicket();

        listener.onStatusChanged(new TicketStatusChangedEvent(TICKET_ID, TicketStatus.IN_PROGRESS,
                TicketStatus.RESOLVED, AGENT_ID));

        verify(notificationPort).notify(eq(CUSTOMER_ID), eq("STATUS_CHANGED"), eq(TICKET_ID), anyString());
        verify(notificationPort, never()).notify(eq(AGENT_ID), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("티켓을 찾지 못하면 로그만 남기고 넘어간다")
    void missingTicket() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> listener.onAssigned(new TicketAssignedEvent(TICKET_ID, AGENT_ID)))
                .doesNotThrowAnyException();
        verifyNoInteractions(notificationPort, mailSender);
    }
}
