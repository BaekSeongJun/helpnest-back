// @owner SSJ
package com.helpnest.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailTemplatesTest {

    @Test
    @DisplayName("해결 메일: 제목·고객명·문의 제목·요약·설문 링크·만료 시각(서울)")
    void resolved() {
        var cmd = new ResolvedMailCommand(1L, "홍길동", "hong@example.com", "T-0001", "환불 문의",
                "답변", "http://localhost:3000/survey/abc", OffsetDateTime.of(2026, 10, 1, 0, 30, 0, 0, ZoneOffset.UTC));

        MailContent mail = MailTemplates.resolved(cmd, "환불이 완료되었습니다.");

        assertThat(mail.subject()).isEqualTo("[HelpNest] 문의(T-0001)가 해결되었습니다");
        assertThat(mail.html()).contains("홍길동", "환불 문의", "환불이 완료되었습니다.",
                "href=\"http://localhost:3000/survey/abc\"", "2026.10.01 09:30", MailTemplates.PRIMARY);
    }

    @Test
    @DisplayName("답변 알림 메일: 제목·문의 보기 링크·답변 200자 절단")
    void agentReply() {
        String longReply = "가".repeat(250);
        var cmd = new AgentReplyMailCommand(1L, "hong@example.com", "T-0002", longReply,
                "http://localhost:3000/inquiry/1");

        MailContent mail = MailTemplates.agentReply(cmd);

        assertThat(mail.subject()).isEqualTo("[HelpNest] 문의(T-0002)에 답변이 등록되었습니다");
        assertThat(mail.html()).contains("가".repeat(200), "문의 보기", "href=\"http://localhost:3000/inquiry/1\"")
                .doesNotContain("가".repeat(201));
    }

    @Test
    @DisplayName("비밀번호 재설정: 회원/비회원 문구 분기, 30분 안내")
    void passwordReset() {
        MailContent member = MailTemplates.passwordReset(
                new PasswordResetMailCommand("a@b.com", "http://localhost:3000/reset-password?token=t1", false));
        MailContent guest = MailTemplates.passwordReset(
                new PasswordResetMailCommand("a@b.com", "http://localhost:3000/inquiry/lookup/reset?token=t2", true));

        assertThat(member.subject()).isEqualTo("[HelpNest] 비밀번호 재설정 안내");
        assertThat(member.html()).contains("30분", "reset-password?token=t1").doesNotContain("조회");
        assertThat(guest.subject()).isEqualTo("[HelpNest] 문의 조회 비밀번호 재설정 안내");
        assertThat(guest.html()).contains("30분", "inquiry/lookup/reset?token=t2");
    }

    @Test
    @DisplayName("사용자 값과 URL 은 HTML 이스케이프")
    void escapesUserInput() {
        var cmd = new ResolvedMailCommand(1L, "<script>alert(1)</script>", "x@y.com", "T-0003", "제목",
                "요약", "http://x/\"onmouseover=\"a&b", OffsetDateTime.now());

        String html = MailTemplates.resolved(cmd, "<b>요약</b>").html();

        assertThat(html).contains("&lt;script&gt;", "&lt;b&gt;요약", "http://x/&quot;onmouseover=&quot;a&amp;b")
                .doesNotContain("<script>", "<b>요약");
    }
}
