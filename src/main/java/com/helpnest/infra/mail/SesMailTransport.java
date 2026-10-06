// @owner SSJ
package com.helpnest.infra.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * prod 메일 전송 (docs/05 §5, SESv2). 실패는 예외로 던지고 DefaultMailSender 가 FAILED 로 기록 →
 * MailRetryScheduler 가 재시도한다. 로컬은 LogMailTransport.
 */
@Component
@Profile("prod")
public class SesMailTransport implements MailTransport {

    private final SesV2Client ses;
    private final String from;

    public SesMailTransport(SesV2Client ses, @Value("${mail.ses.from}") String from) {
        Assert.hasText(from, "SES_FROM_EMAIL 환경변수가 필요합니다 (prod)");
        this.ses = ses;
        this.from = from;
    }

    @Override
    public MailLog.Status send(String to, String subject, String html) {
        Message message = Message.builder()
                .subject(utf8(subject))
                .body(Body.builder().html(utf8(html)).build())
                .build();
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(EmailContent.builder().simple(message).build())
                .build());
        return MailLog.Status.SENT;
    }

    private static Content utf8(String data) {
        return Content.builder().data(data).charset("UTF-8").build();
    }
}
