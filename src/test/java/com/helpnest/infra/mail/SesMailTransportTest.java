// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

class SesMailTransportTest {

    private final SesV2Client ses = mock(SesV2Client.class);
    private final SesMailTransport transport = new SesMailTransport(ses, "no-reply@helpnest.kro.kr");

    @Test
    @DisplayName("from·to·제목·HTML 본문을 UTF-8 로 보내고 SENT")
    void sends() {
        MailLog.Status status = transport.send("c@example.com", "[HelpNest] 해결 안내", "<p>안녕하세요</p>");

        ArgumentCaptor<SendEmailRequest> req = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(req.capture());
        Message m = req.getValue().content().simple();
        assertThat(status).isEqualTo(MailLog.Status.SENT);
        assertThat(req.getValue().fromEmailAddress()).isEqualTo("no-reply@helpnest.kro.kr");
        assertThat(req.getValue().destination().toAddresses()).containsExactly("c@example.com");
        assertThat(m.subject().data()).isEqualTo("[HelpNest] 해결 안내");
        assertThat(m.subject().charset()).isEqualTo("UTF-8");
        assertThat(m.body().html().data()).isEqualTo("<p>안녕하세요</p>");
        assertThat(m.body().html().charset()).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("SES 실패는 예외로 전파 (DefaultMailSender 가 FAILED·재시도 처리), 발신 주소 미설정이면 생성 실패")
    void failures() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder().message("throttled").build());

        assertThatThrownBy(() -> transport.send("c@example.com", "s", "b")).isInstanceOf(SesV2Exception.class);
        assertThatThrownBy(() -> new SesMailTransport(ses, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SES_FROM_EMAIL");
    }
}
