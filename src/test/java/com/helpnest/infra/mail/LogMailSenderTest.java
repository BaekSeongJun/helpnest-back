// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogMailSenderTest {

    private final LogMailSender sender = new LogMailSender();

    @Test
    @DisplayName("로그에는 유형·티켓번호·마스킹 이메일만, 원문 이메일·토큰 URL 없음")
    void noSensitiveDataInLog(CapturedOutput output) {
        sender.sendResolvedMail(new ResolvedMailCommand(1L, "홍길동", "hong@example.com", "T-0001", "제목",
                "요약", "http://localhost:3000/survey/survey-token-123", OffsetDateTime.now()));
        sender.sendAgentReplyMail(new AgentReplyMailCommand(1L, "hong@example.com", "T-0001", "답변",
                "http://localhost:3000/inquiry/1"));
        sender.sendPasswordResetMail(new PasswordResetMailCommand("hong@example.com",
                "http://localhost:3000/reset-password?token=reset-token-456", true));

        assertThat(output).contains("RESOLVED", "AGENT_REPLY", "GUEST_PASSWORD_RESET", "T-0001", "h***@example.com")
                .doesNotContain("hong@example.com", "survey-token-123", "reset-token-456");
    }

    @Test
    @DisplayName("이메일 마스킹")
    void mask() {
        assertThat(LogMailSender.mask("hong@example.com")).isEqualTo("h***@example.com");
        assertThat(LogMailSender.mask("@example.com")).isEqualTo("***");
        assertThat(LogMailSender.mask("invalid")).isEqualTo("***");
        assertThat(LogMailSender.mask(null)).isEqualTo("***");
    }
}
