// @owner BSJ
package com.helpnest.domain.survey.listener;

import com.helpnest.domain.member.port.MemberInfo;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.port.ResolvedMailInfo;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.infra.mail.MailSender;
import com.helpnest.infra.mail.ResolvedMailCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 티켓 상태 변경을 받아 설문을 만들고 만료한다 (docs/02 §5.1).
 * <ul>
 *   <li>to=RESOLVED — 설문 생성(재해결이면 재발급) 후 결과 메일 + 설문 링크 발송</li>
 *   <li>RESOLVED→IN_PROGRESS(고객 재문의) — 미제출 설문 만료</li>
 * </ul>
 * 커밋 후 비동기로 돈다. 실패해도 상태 전이는 이미 끝났으므로 던지지 않고 로그만 남긴다
 * (메일 전송 실패 재시도는 신수진의 MAIL_LOG).
 */
@Slf4j
@Component
public class SurveyListener {

    private final SurveyService surveyService;
    private final TicketQueryPort ticketQueryPort;
    private final MemberQueryPort memberQueryPort;
    private final MailSender mailSender;
    private final String frontOrigin;

    public SurveyListener(SurveyService surveyService, TicketQueryPort ticketQueryPort,
            MemberQueryPort memberQueryPort, MailSender mailSender,
            @Value("${app.front-origin}") String frontOrigin) {
        this.surveyService = surveyService;
        this.ticketQueryPort = ticketQueryPort;
        this.memberQueryPort = memberQueryPort;
        this.mailSender = mailSender;
        this.frontOrigin = frontOrigin;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TicketStatusChangedEvent event) {
        try {
            if (event.to() == TicketStatus.RESOLVED) {
                sendSurvey(event.ticketId());
            } else if (event.from() == TicketStatus.RESOLVED && event.to() == TicketStatus.IN_PROGRESS) {
                surveyService.expire(event.ticketId());
            }
        } catch (Exception e) {
            log.warn("[survey] 처리 실패 ticketId={} {}→{} cause={}",
                    event.ticketId(), event.from(), event.to(), e.toString());
        }
    }

    private void sendSurvey(Long ticketId) {
        Survey survey = surveyService.issueOrReissue(ticketId);
        ResolvedMailInfo info = ticketQueryPort.getResolvedMailInfo(ticketId);

        // 회원이면 이름·이메일을 회원 포트에서, 비회원이면 티켓에서 가져온다
        String name = info.guestName();
        String email = info.guestEmail();
        if (info.customerId() != null) {
            MemberInfo member = memberQueryPort.getMember(info.customerId());
            name = member.name();
            email = member.email();
        }

        mailSender.sendResolvedMail(new ResolvedMailCommand(ticketId, name, email, info.ticketNo(),
                info.title(), info.lastPublicReply(), frontOrigin + "/survey/" + survey.getToken(),
                survey.getExpiresAt()));
    }
}
