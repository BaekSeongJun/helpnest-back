// @owner SSJ
package com.helpnest.infra.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬용: 실제 발송 없이 콘솔 로그 + MAIL_LOG(LOGGED) 저장. prod는 SesMailSender.
 * 이메일 원문·토큰 URL은 로그 금지(docs/10 §3.3) → 유형·티켓번호·마스킹 이메일만 남긴다.
 */
@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class LogMailSender implements MailSender {

    private final MailLogRepository mailLogRepository;

    @Override
    public void sendResolvedMail(ResolvedMailCommand command) {
        record(command.ticketId(), command.ticketNo(), command.email(), MailLog.Type.RESOLVED_SURVEY);
    }

    @Override
    public void sendAgentReplyMail(AgentReplyMailCommand command) {
        record(command.ticketId(), command.ticketNo(), command.email(), MailLog.Type.AGENT_REPLY);
    }

    @Override
    public void sendPasswordResetMail(PasswordResetMailCommand command) {
        MailLog.Type type = command.guest() ? MailLog.Type.GUEST_PASSWORD_RESET : MailLog.Type.PASSWORD_RESET;
        record(null, null, command.email(), type);
    }

    private void record(Long ticketId, String ticketNo, String email, MailLog.Type type) {
        log.info("[mail] {} ticketNo={} to={}", type, ticketNo, mask(email));
        mailLogRepository.save(MailLog.builder()
                .ticketId(ticketId)
                .toEmail(email)
                .mailType(type)
                .status(MailLog.Status.LOGGED)
                .build());
    }

    // hong@example.com → h***@example.com
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
