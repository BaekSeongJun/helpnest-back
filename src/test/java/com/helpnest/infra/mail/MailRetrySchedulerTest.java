// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailRetrySchedulerTest {

    private final MailLogRepository repository = mock(MailLogRepository.class);

    /** 수신자별로 성공/실패를 정하는 가짜 전송 (로컬 transport 는 실패하지 않으므로) */
    private final List<String> sent = new ArrayList<>();
    private final MailTransport transport = (to, subject, html) -> {
        if (to.startsWith("fail")) {
            throw new IllegalStateException("ses down");
        }
        sent.add(to + "|" + subject + "|" + html);
        return MailLog.Status.SENT;
    };

    private final MailRetryScheduler scheduler = new MailRetryScheduler(repository, transport);

    private static MailLog failed(String email) {
        MailLog mail = MailLog.builder().toEmail(email).mailType(MailLog.Type.AGENT_REPLY)
                .subject("제목").body("<p>본문</p>").build();
        mail.markFailed("first failure");
        return mail;
    }

    @Test
    @DisplayName("재시도 성공: 저장된 제목·본문으로 재전송, SENT·sentAt·errorMsg 초기화")
    void retrySuccess() {
        MailLog mail = failed("ok@example.com");
        when(repository.findTop50ByStatusAndRetryCountLessThanOrderByIdAsc(MailLog.Status.FAILED, 3))
                .thenReturn(List.of(mail));

        scheduler.retryFailed();

        assertThat(sent).containsExactly("ok@example.com|제목|<p>본문</p>");
        assertThat(mail.getStatus()).isEqualTo(MailLog.Status.SENT);
        assertThat(mail.getSentAt()).isNotNull();
        assertThat(mail.getErrorMsg()).isNull();
        verify(repository).save(mail);
    }

    @Test
    @DisplayName("재시도 실패: FAILED 유지·retryCount 증가, 다른 건은 계속 처리")
    void retryFailureContinues() {
        MailLog bad = failed("fail@example.com");
        MailLog good = failed("ok@example.com");
        when(repository.findTop50ByStatusAndRetryCountLessThanOrderByIdAsc(MailLog.Status.FAILED, 3))
                .thenReturn(List.of(bad, good));

        scheduler.retryFailed();

        assertThat(bad.getStatus()).isEqualTo(MailLog.Status.FAILED);
        assertThat(bad.getRetryCount()).isEqualTo(1);
        assertThat(bad.getErrorMsg()).contains("ses down");
        assertThat(good.getStatus()).isEqualTo(MailLog.Status.SENT);
        verify(repository).save(bad);
        verify(repository).save(good);
    }
}
