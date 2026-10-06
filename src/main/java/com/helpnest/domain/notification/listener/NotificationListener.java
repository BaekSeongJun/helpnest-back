// @owner PMJ
package com.helpnest.domain.notification.listener;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.domain.ticket.entity.Ticket;
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

import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 이벤트를 받아 웹 알림을 저장·발송하고, 상담원 공개 답변은 고객에게 메일로도 알린다
 * (docs/02 §5.1, docs/03 §2.1 notification.type).
 *
 * <p>구독 방식은 {@code AiClassifyListener} 와 같다 — 커밋 후 비동기. 알림은 업무의 부수 효과이므로
 * 실패해도 배정·답변·상태 전이를 되돌리지 않고 예외를 밖으로 던지지 않는다
 * ({@code SurveyListener} 와 같은 이유).
 *
 * <h2>내부 메모는 아무것도 보내지 않는다</h2>
 * {@code ReplyCreatedEvent.isInternal()} 이 true 면 즉시 돌아간다. 이 분기가 이 클래스에서 가장
 * 위험한 지점이다 — 놓치면 상담원끼리 주고받은 메모가 그대로 고객 알림·메일로 나간다
 * ({@code ReplyCreatedEvent} Javadoc 의 경고).
 *
 * <h2>웹 알림은 즉시, 메일 묶음은 MailSender 가 한다</h2>
 * 로드맵의 "10분 묶음"은 메일에만 걸리는 한정자다. 웹 알림을 지연시키면 상담 중인 고객이 답변이
 * 달린 것을 모른다. 그리고 <b>묶음을 여기서 다시 구현하지 않는다</b> —
 * {@code DefaultMailSender.sendAgentReplyMail} 이 MAIL_LOG 를 기준으로 같은 티켓의 10분 내 연속
 * 발송을 이미 억제하며({@code AgentReplyMailCommand} Javadoc 이 "공개 답변마다 호출하면 된다"고
 * 계약한 부분) 그 판정에 테스트도 있다({@code DefaultMailSenderDbTest}). 발송 시각을 여기서 또
 * 들고 있으면 두 기록이 어긋날 때 같은 답변 메일이 두 통 나간다.
 *
 * <h2>비회원은 웹 알림 대상이 아니다</h2>
 * {@code notification.receiver_id} 가 member FK NOT NULL 이라 DB 수준에서도 불가능하다
 * ({@code NotificationPort.notify} Javadoc). 비회원 티켓은 메일만 보낸다.
 */
@Slf4j
@Component
public class NotificationListener {

    /** 알림 문구·메일 미리보기에 넣을 답변 앞부분. message 가 VARCHAR(300) 이라 넉넉히 들어간다 */
    private static final int PREVIEW_LENGTH = 100;

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final MemberQueryPort memberQueryPort;
    private final NotificationPort notificationPort;
    private final MailSender mailSender;
    private final String frontOrigin;

    public NotificationListener(TicketRepository ticketRepository,
            TicketReplyRepository ticketReplyRepository, MemberQueryPort memberQueryPort,
            NotificationPort notificationPort, MailSender mailSender,
            @Value("${app.front-origin}") String frontOrigin) {
        this.ticketRepository = ticketRepository;
        this.ticketReplyRepository = ticketReplyRepository;
        this.memberQueryPort = memberQueryPort;
        this.notificationPort = notificationPort;
        this.mailSender = mailSender;
        this.frontOrigin = frontOrigin;
    }

    /** 배정된 상담원에게 알린다 (FR-ASN-01). 자동 배정·수동 재배정이 같은 이벤트로 들어온다 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssigned(TicketAssignedEvent event) {
        guard("배정", event.ticketId(), () -> {
            Ticket ticket = ticket(event.ticketId());
            notificationPort.notify(event.agentId(), NotificationType.ASSIGNED.name(), event.ticketId(),
                    "새 문의가 배정되었습니다. (%s) %s".formatted(ticket.getTicketNo(), ticket.getTitle()));
        });
    }

    /**
     * 상태 변경을 회원 고객과 담당 상담원에게 알린다.
     *
     * <p>상태를 바꾼 당사자에게는 보내지 않는다 — 자기가 누른 버튼의 결과를 알림으로 다시 받으면
     * 벨이 자기 행동으로 채워져 정작 남이 만든 변화를 놓친다. {@code actorId} 는 시스템 자동 전이
     * (SLA 초과·72시간 자동 종료)면 null 이며({@code TicketStatusChangedEvent} Javadoc) 그때는
     * 제외할 당사자가 없으므로 둘 다 받는다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStatusChanged(TicketStatusChangedEvent event) {
        guard("상태 변경", event.ticketId(), () -> {
            Ticket ticket = ticket(event.ticketId());
            String message = "문의(%s) 상태가 %s(으)로 변경되었습니다."
                    .formatted(ticket.getTicketNo(), label(event.to()));
            notifyUnlessActor(ticket.getCustomerId(), event, message);
            notifyUnlessActor(ticket.getAgentId(), event, message);
        });
    }

    /**
     * 답변을 작성자 종류별로 나눠 알린다.
     * <ul>
     *   <li>고객·비회원 답글 → 담당 상담원에게 CUSTOMER_REPLY</li>
     *   <li>상담원 공개 답변 → 회원 고객에게 AGENT_REPLY 웹 알림 + 고객 메일</li>
     *   <li>내부 메모 → 아무것도 보내지 않는다(클래스 주석)</li>
     * </ul>
     * writerType 이 String 인 것은 이벤트의 규약이고 값 집합은 내 {@link WriterType} 이므로 여기서
     * enum 으로 되돌려 분기를 컴파일러에 맡긴다 — 문자열 비교로 두면 SYSTEM 같은 값이 조용히
     * 고객 답글 분기로 빠진다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReplyCreated(ReplyCreatedEvent event) {
        if (event.isInternal()) {
            log.debug("[noti] 내부 메모라 알림·메일 없음 ticketId={} replyId={}",
                    event.ticketId(), event.replyId());
            return;
        }
        guard("답변", event.ticketId(), () -> {
            Ticket ticket = ticket(event.ticketId());
            String preview = preview(reply(event.replyId()).getContent());

            switch (WriterType.valueOf(event.writerType())) {
                case AGENT -> notifyAgentReply(ticket, preview);
                case CUSTOMER, GUEST -> notificationPort.notify(ticket.getAgentId(),
                        NotificationType.CUSTOMER_REPLY.name(), ticket.getId(),
                        "문의(%s)에 고객 답글이 등록되었습니다. %s".formatted(ticket.getTicketNo(), preview));
                // 시스템이 남긴 답변은 알릴 상대가 없다(고객에게는 안내 문구, 상담원에게는 자기 처리 결과)
                case SYSTEM -> log.debug("[noti] SYSTEM 답변은 알림 대상이 아니다 ticketId={}", ticket.getId());
            }
        });
    }

    /**
     * 웹 알림을 먼저, 메일을 뒤에 보낸다. 메일 실패가 웹 알림을 막으면 안 되고 웹 알림 실패는
     * 어댑터가 삼키므로, 순서를 이렇게 두면 둘이 서로를 가리지 않는다.
     */
    private void notifyAgentReply(Ticket ticket, String preview) {
        if (ticket.isMemberTicket()) {
            notificationPort.notify(ticket.getCustomerId(), NotificationType.AGENT_REPLY.name(),
                    ticket.getId(),
                    "문의(%s)에 상담원 답변이 등록되었습니다. %s".formatted(ticket.getTicketNo(), preview));
        }
        sendAgentReplyMail(ticket, preview);
    }

    /**
     * 수신자는 회원이면 회원 포트의 이메일, 비회원이면 티켓에 적힌 이메일이다
     * ({@code SurveyListener} 와 같은 규칙).
     *
     * <p>미리보기에 내부 메모가 섞일 길은 없다 — 이 메서드의 호출부가 공개 답변 분기 하나뿐이다.
     */
    private void sendAgentReplyMail(Ticket ticket, String preview) {
        try {
            String email = ticket.isMemberTicket()
                    ? memberQueryPort.getMember(ticket.getCustomerId()).email()
                    : ticket.getGuestEmail();
            if (email == null || email.isBlank()) {
                log.warn("[noti] 답변 알림 메일 수신자가 없다 ticketNo={}", ticket.getTicketNo());
                return;
            }
            mailSender.sendAgentReplyMail(new AgentReplyMailCommand(ticket.getId(), email,
                    ticket.getTicketNo(), preview, ticketUrl(ticket)));
        } catch (Exception e) {
            // 웹 알림은 이미 저장됐으므로 메일만 포기한다. 재전송은 신수진의 MAIL_LOG 가 맡는다
            log.warn("[noti] 답변 알림 메일 실패 ticketNo={} cause={}", ticket.getTicketNo(), e.toString());
        }
    }

    /** 회원은 내 문의 상세로, 비회원은 조회 화면으로 보낸다(비회원 상세는 토큰 없이 열 수 없다) */
    private String ticketUrl(Ticket ticket) {
        return ticket.isMemberTicket()
                ? frontOrigin + "/my/inquiries/" + ticket.getId()
                : frontOrigin + "/inquiry/lookup";
    }

    private void notifyUnlessActor(Long receiverId, TicketStatusChangedEvent event, String message) {
        if (receiverId == null || Objects.equals(receiverId, event.actorId())) {
            return;
        }
        notificationPort.notify(receiverId, NotificationType.STATUS_CHANGED.name(), event.ticketId(), message);
    }

    /** 문구는 프론트 {@code config/badge.ts} 의 배지 라벨과 같은 말을 쓴다(화면과 알림이 다르면 혼란) */
    private String label(TicketStatus status) {
        return switch (status) {
            case RECEIVED -> "접수";
            case ASSIGNED -> "배정";
            case IN_PROGRESS -> "처리중";
            case RESOLVED -> "해결";
            case CLOSED -> "종료";
        };
    }

    /** 줄바꿈을 접어 한 줄로 만든다 — 알림 벨과 메일 본문 모두 한 줄 미리보기로 쓴다 */
    private String preview(String content) {
        String flat = content.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_LENGTH ? flat : flat.substring(0, PREVIEW_LENGTH) + "…";
    }

    private Ticket ticket(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalStateException("티켓이 없다 ticketId=" + ticketId));
    }

    private TicketReply reply(Long replyId) {
        return ticketReplyRepository.findById(replyId)
                .orElseThrow(() -> new IllegalStateException("답변이 없다 replyId=" + replyId));
    }

    /** 커밋 후에 도는 경로라 예외를 던져도 되돌릴 업무가 없다. 로그만 남기고 넘긴다 */
    private void guard(String what, Long ticketId, Runnable body) {
        try {
            body.run();
        } catch (Exception e) {
            log.warn("[noti] {} 알림 처리 실패 ticketId={} cause={}", what, ticketId, e.toString());
        }
    }
}
