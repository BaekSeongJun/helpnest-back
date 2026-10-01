// @owner SSJ
package com.helpnest.infra.mail;

/** 렌더링된 메일의 실제 전송. 로컬은 LogMailTransport, prod 는 SES(S3). 실패 시 예외 */
public interface MailTransport {

    /** @return SENT(실제 발송) 또는 LOGGED(로컬, 발송 없음) */
    MailLog.Status send(String to, String subject, String html);
}
