// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

/** 실제 PostgreSQL(Flyway 적용 + ddl-auto validate)에서 MAIL_LOG 저장 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(LogMailSender.class)
class LogMailSenderDbTest {

    @Autowired
    private LogMailSender sender;

    @Autowired
    private MailLogRepository mailLogRepository;

    @Test
    @DisplayName("메일 호출 시 mail_log 에 LOGGED 행 저장")
    void savesLoggedRow() {
        sender.sendPasswordResetMail(new PasswordResetMailCommand("hong@example.com",
                "http://localhost:3000/inquiry/lookup/reset?token=t", true));

        assertThat(mailLogRepository.findAll()).singleElement().satisfies(mail -> {
            assertThat(mail.getStatus()).isEqualTo(MailLog.Status.LOGGED);
            assertThat(mail.getMailType()).isEqualTo(MailLog.Type.GUEST_PASSWORD_RESET);
            assertThat(mail.getToEmail()).isEqualTo("hong@example.com");
            assertThat(mail.getTicketId()).isNull();
            assertThat(mail.getCreatedAt()).isNotNull();
        });
    }
}
