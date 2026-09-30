// @owner SSJ
package com.helpnest.infra.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬용: 실제 발송 없이 콘솔 로그만. prod는 SesMailSender.
 * 이메일 원문·토큰 URL은 로그 금지(docs/10 §3.3) → 유형·티켓번호·마스킹 이메일만 남긴다.
 */
// ponytail: MAIL_LOG(LOGGED) 저장은 MAIL_LOG 테이블 태스크에서 연결
@Slf4j
@Component
@Profile("!prod")
public class LogMailSender implements MailSender {

    @Override
    public void sendResolvedMail(ResolvedMailCommand command) {
        log.info("[mail] RESOLVED ticketNo={} to={}", command.ticketNo(), mask(command.email()));
    }

    @Override
    public void sendAgentReplyMail(AgentReplyMailCommand command) {
        log.info("[mail] AGENT_REPLY ticketNo={} to={}", command.ticketNo(), mask(command.email()));
    }

    @Override
    public void sendPasswordResetMail(PasswordResetMailCommand command) {
        String type = command.guest() ? "GUEST_PASSWORD_RESET" : "PASSWORD_RESET";
        log.info("[mail] {} to={}", type, mask(command.email()));
    }

    // hong@example.com → h***@example.com
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at < 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
