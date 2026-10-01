// @owner SSJ
package com.helpnest.infra.mail;

import com.helpnest.infra.llm.LlmClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 렌더링(MailTemplates) → 전송(MailTransport) → MAIL_LOG 저장 (docs/05 §5).
 * 전송 실패는 FAILED 로 기록하고 호출자에 예외를 던지지 않는다 → 재시도 스케줄러가 처리.
 * 로그에는 유형·티켓번호·마스킹 이메일만 남긴다(docs/10 §3.3).
 */
@Slf4j
@Component
public class DefaultMailSender implements MailSender {

    static final Duration AGENT_REPLY_BUNDLE = Duration.ofMinutes(10);

    static final String SUMMARY_PROMPT = """
            너는 고객센터 메일 작성 도우미다. 상담원의 최종 답변을 고객이 한눈에 알 수 있게
            한국어 1문장(80자 이내)으로 요약한다. 새로운 사실을 지어내지 않는다.
            응답은 JSON {"summary": "..."} 하나만.
            """;

    /** LLM 요약 응답 */
    public record ReplySummary(String summary) {
    }

    private final MailLogRepository mailLogRepository;
    private final MailTransport transport;
    private final LlmClient llmClient;
    private final Clock clock;

    @Autowired
    public DefaultMailSender(MailLogRepository mailLogRepository, MailTransport transport, LlmClient llmClient) {
        this(mailLogRepository, transport, llmClient, Clock.systemUTC());
    }

    DefaultMailSender(MailLogRepository mailLogRepository, MailTransport transport, LlmClient llmClient,
            Clock clock) {
        this.mailLogRepository = mailLogRepository;
        this.transport = transport;
        this.llmClient = llmClient;
        this.clock = clock;
    }

    @Override
    public void sendResolvedMail(ResolvedMailCommand command) {
        MailContent content = MailTemplates.resolved(command, summarize(command.finalReply()));
        send(command.ticketId(), command.ticketNo(), command.email(), MailLog.Type.RESOLVED_SURVEY, content);
    }

    @Override
    public void sendAgentReplyMail(AgentReplyMailCommand command) {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(AGENT_REPLY_BUNDLE);
        if (mailLogRepository.existsByTicketIdAndMailTypeAndStatusInAndCreatedAtAfter(command.ticketId(),
                MailLog.Type.AGENT_REPLY, List.of(MailLog.Status.SENT, MailLog.Status.LOGGED), cutoff)) {
            log.info("[mail] AGENT_REPLY skip(10분 묶음) ticketNo={}", command.ticketNo());
            return;
        }
        send(command.ticketId(), command.ticketNo(), command.email(), MailLog.Type.AGENT_REPLY,
                MailTemplates.agentReply(command));
    }

    @Override
    public void sendPasswordResetMail(PasswordResetMailCommand command) {
        MailLog.Type type = command.guest() ? MailLog.Type.GUEST_PASSWORD_RESET : MailLog.Type.PASSWORD_RESET;
        send(null, null, command.email(), type, MailTemplates.passwordReset(command));
    }

    // ponytail: 전송 후 1회 저장 — 전송 직후 앱이 죽으면 기록 누락, 필요하면 PENDING 선저장으로 전환
    private void send(Long ticketId, String ticketNo, String email, MailLog.Type type, MailContent content) {
        MailLog mail = MailLog.builder()
                .ticketId(ticketId)
                .toEmail(email)
                .mailType(type)
                .subject(content.subject())
                .body(content.html())
                .build();
        try {
            mail.markDelivered(transport.send(email, content.subject(), content.html()), OffsetDateTime.now(clock));
            log.info("[mail] {} {} ticketNo={} to={}", type, mail.getStatus(), ticketNo, mask(email));
        } catch (Exception e) {
            mail.markFailed(e.toString());
            log.warn("[mail] {} FAILED ticketNo={} to={} cause={}", type, ticketNo, mask(email), e.toString());
        }
        mailLogRepository.save(mail);
    }

    /** LLM 1문장 요약, 실패·빈값이면 앞 200자 (docs/05 §5) */
    String summarize(String finalReply) {
        try {
            ReplySummary r = llmClient.structured(SUMMARY_PROMPT, finalReply, ReplySummary.class);
            if (r != null && r.summary() != null && !r.summary().isBlank()) {
                return r.summary().strip();
            }
        } catch (Exception e) {
            log.warn("[mail] 답변 요약 실패, 앞 200자 사용 cause={}", e.toString());
        }
        return MailTemplates.preview(finalReply);
    }

    // hong@example.com → h***@example.com
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
