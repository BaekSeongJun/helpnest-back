// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.infra.llm.LlmClient;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class DefaultMailSenderTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");

    private final MailLogRepository repository = mock(MailLogRepository.class);
    private final MailTransport transport = mock(MailTransport.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final DefaultMailSender sender = new DefaultMailSender(repository, transport, llm,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static ResolvedMailCommand resolved(String finalReply) {
        return new ResolvedMailCommand(1L, "홍길동", "hong@example.com", "T-0001", "환불 문의", finalReply,
                "http://localhost:3000/survey/survey-token-123", OffsetDateTime.now());
    }

    private static AgentReplyMailCommand agentReply() {
        return new AgentReplyMailCommand(1L, "hong@example.com", "T-0001", "답변",
                "http://localhost:3000/inquiry/1");
    }

    private MailLog savedMail() {
        ArgumentCaptor<MailLog> captor = ArgumentCaptor.forClass(MailLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("전송 성공: transport 결과 상태(LOGGED)·제목·본문·sentAt 저장")
    void savesDelivered() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(MailLog.Status.LOGGED);

        sender.sendPasswordResetMail(new PasswordResetMailCommand("hong@example.com",
                "http://localhost:3000/reset-password?token=t1", false));

        MailLog mail = savedMail();
        assertThat(mail.getStatus()).isEqualTo(MailLog.Status.LOGGED);
        assertThat(mail.getMailType()).isEqualTo(MailLog.Type.PASSWORD_RESET);
        assertThat(mail.getSubject()).isEqualTo("[HelpNest] 비밀번호 재설정 안내");
        assertThat(mail.getBody()).contains("reset-password?token=t1");
        assertThat(mail.getSentAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("전송 실패: FAILED + errorMsg 저장, 호출자에 예외 전파 없음")
    void failureAbsorbed() {
        when(transport.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("smtp down"));

        assertThatCode(() -> sender.sendAgentReplyMail(agentReply())).doesNotThrowAnyException();

        MailLog mail = savedMail();
        assertThat(mail.getStatus()).isEqualTo(MailLog.Status.FAILED);
        assertThat(mail.getErrorMsg()).contains("smtp down");
        assertThat(mail.getSentAt()).isNull();
        assertThat(mail.getBody()).isNotBlank(); // 재시도용
    }

    @Test
    @DisplayName("AGENT_REPLY: 10분 내 발송 기록 있으면 skip")
    void agentReplyBundled() {
        when(repository.existsByTicketIdAndMailTypeAndStatusInAndCreatedAtAfter(eq(1L), eq(MailLog.Type.AGENT_REPLY),
                any(), any())).thenReturn(true);

        sender.sendAgentReplyMail(agentReply());

        verify(transport, never()).send(anyString(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("AGENT_REPLY: 기준 시각은 지금-10분, 기록 없으면 발송")
    void agentReplySentAfterWindow() {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(MailLog.Status.SENT);

        sender.sendAgentReplyMail(agentReply());

        verify(repository).existsByTicketIdAndMailTypeAndStatusInAndCreatedAtAfter(eq(1L),
                eq(MailLog.Type.AGENT_REPLY), any(),
                eq(OffsetDateTime.ofInstant(NOW.minusSeconds(600), ZoneOffset.UTC)));
        assertThat(savedMail().getStatus()).isEqualTo(MailLog.Status.SENT);
    }

    @Test
    @DisplayName("해결 메일: LLM 요약 성공 시 요약을 본문에 사용")
    void resolvedUsesSummary() {
        when(llm.structured(anyString(), anyString(), eq(DefaultMailSender.ReplySummary.class)))
                .thenReturn(new DefaultMailSender.ReplySummary(" 환불이 완료되었습니다. "));
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(MailLog.Status.LOGGED);

        sender.sendResolvedMail(resolved("긴 답변 원문"));

        assertThat(savedMail().getBody()).contains("환불이 완료되었습니다.").doesNotContain("긴 답변 원문");
    }

    @Test
    @DisplayName("요약 실패·빈 응답이면 답변 앞 200자")
    void summaryFallback() {
        String reply = "가".repeat(250);
        when(llm.structured(anyString(), anyString(), eq(DefaultMailSender.ReplySummary.class)))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(new DefaultMailSender.ReplySummary(" "));

        assertThat(sender.summarize(reply)).isEqualTo("가".repeat(200) + "…");
        assertThat(sender.summarize("짧은 답변")).isEqualTo("짧은 답변");
    }

    @Test
    @DisplayName("로그에는 유형·티켓번호·마스킹 이메일만, 원문 이메일·토큰 URL 없음")
    void noSensitiveDataInLog(CapturedOutput output) {
        when(transport.send(anyString(), anyString(), anyString())).thenReturn(MailLog.Status.LOGGED);

        sender.sendResolvedMail(resolved("답변"));
        sender.sendPasswordResetMail(new PasswordResetMailCommand("hong@example.com",
                "http://localhost:3000/reset-password?token=reset-token-456", true));

        assertThat(output).contains("RESOLVED_SURVEY", "GUEST_PASSWORD_RESET", "T-0001", "h***@example.com")
                .doesNotContain("hong@example.com", "survey-token-123", "reset-token-456");
    }

    @Test
    @DisplayName("이메일 마스킹")
    void mask() {
        assertThat(DefaultMailSender.mask("hong@example.com")).isEqualTo("h***@example.com");
        assertThat(DefaultMailSender.mask("@example.com")).isEqualTo("***");
        assertThat(DefaultMailSender.mask("invalid")).isEqualTo("***");
        assertThat(DefaultMailSender.mask(null)).isEqualTo("***");
    }
}
