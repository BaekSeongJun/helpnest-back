// @owner SSJ
package com.helpnest.infra.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 로컬용: 실제 발송 없이 로그만. 본문은 토큰 URL 이 있어 출력하지 않는다(docs/10 §3.3) → MAIL_LOG.body 로 확인 */
@Slf4j
@Component
@Profile("!prod")
public class LogMailTransport implements MailTransport {

    @Override
    public MailLog.Status send(String to, String subject, String html) {
        log.info("[mail:local] to={} subject={}", DefaultMailSender.mask(to), subject);
        return MailLog.Status.LOGGED;
    }
}
