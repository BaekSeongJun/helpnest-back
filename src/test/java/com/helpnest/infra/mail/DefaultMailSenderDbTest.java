// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpnest.infra.llm.MockLlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 PostgreSQL(Flyway 적용 + ddl-auto validate)에서 MAIL_LOG 저장·10분 묶음 쿼리 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DefaultMailSender.class, LogMailTransport.class, MockLlmClient.class})
class DefaultMailSenderDbTest {

    @Autowired
    private DefaultMailSender sender;

    @Autowired
    private MailLogRepository mailLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("메일 호출 시 mail_log 에 LOGGED + 제목·본문 저장")
    void savesLoggedRowWithContent() {
        sender.sendPasswordResetMail(new PasswordResetMailCommand("hong@example.com",
                "http://localhost:3000/inquiry/lookup/reset?token=t", true));

        assertThat(mailLogRepository.findAll()).singleElement().satisfies(mail -> {
            assertThat(mail.getStatus()).isEqualTo(MailLog.Status.LOGGED);
            assertThat(mail.getMailType()).isEqualTo(MailLog.Type.GUEST_PASSWORD_RESET);
            assertThat(mail.getTicketId()).isNull();
            assertThat(mail.getSubject()).isEqualTo("[HelpNest] 문의 조회 비밀번호 재설정 안내");
            assertThat(mail.getBody()).contains("inquiry/lookup/reset?token=t");
            assertThat(mail.getSentAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("AGENT_REPLY 10분 묶음: 연속 호출은 1통, 기존 기록이 10분 지났으면 다시 발송")
    void agentReplyBundling() {
        // ticket FK 를 피하려 ticketId=null (파생 쿼리가 IS NULL 로 비교)
        AgentReplyMailCommand cmd = new AgentReplyMailCommand(null, "hong@example.com", "T-0001", "답변",
                "http://localhost:3000/inquiry/1");

        sender.sendAgentReplyMail(cmd);
        sender.sendAgentReplyMail(cmd);
        assertThat(mailLogRepository.count()).isEqualTo(1);

        jdbcTemplate.update("UPDATE mail_log SET created_at = NOW() - INTERVAL '11 minutes'");
        sender.sendAgentReplyMail(cmd);
        assertThat(mailLogRepository.count()).isEqualTo(2);
    }
}
