// @owner SSJ
package com.helpnest.domain.auth.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증 (back #117). 테스트 트랜잭션은 커밋되지 않아 메일 리스너·비동기 재발송은 이 회원을 못 본다
 * → 링크는 issueLink 를 직접 불러 받는다(같은 트랜잭션).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmailVerificationFlowTest {

    private static final String EMAIL = "verify-flow@helpnest.local";
    private static final String PASSWORD = "Test1234!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    EmailVerificationService verificationService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;

    private Member member;

    @BeforeEach
    void pendingMember() {
        member = memberRepository.save(Member.builder()
                .email(EMAIL)
                .password(passwordEncoder.encode(PASSWORD))
                .name("인증테스트")
                .role(MemberRole.CUSTOMER)
                .status(MemberStatus.PENDING)
                .build());
    }

    @Test
    @DisplayName("인증 성공: PENDING → ACTIVE, 인증 전 로그인 403·후 200, 링크는 FRONT_ORIGIN/verify-email?token=")
    void verifySuccess() throws Exception {
        String link = verificationService.issueLink(member.getId());
        assertThat(link).startsWith("http://localhost:3000/verify-email?token=");
        login().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_NOT_VERIFIED"));

        verify(token(link)).andExpect(status().isOk());

        em.flush();
        em.clear();
        Member verified = memberRepository.findById(member.getId()).orElseThrow();
        assertThat(verified.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(verified.getEmailVerifiedAt()).isNotNull();
        login().andExpect(status().isOk());
    }

    @Test
    @DisplayName("거부: 재사용·만료·빈 토큰은 같은 400 AUTH_RESET_TOKEN_INVALID")
    void rejectsUsedAndExpired() throws Exception {
        String used = token(verificationService.issueLink(member.getId()));
        verify(used).andExpect(status().isOk());
        verify(used).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));

        String expired = token(verificationService.issueLink(member.getId()));
        jdbc.update("UPDATE password_reset_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE used_at IS NULL");
        em.clear();   // JDBC 로 바꾼 값을 1차 캐시가 가리지 않게
        verify(expired).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));

        verify(" ").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("재발송: 새 링크를 받으면 이전 링크는 무효")
    void resendInvalidatesPrevious() throws Exception {
        String first = token(verificationService.issueLink(member.getId()));
        String second = token(verificationService.issueLink(member.getId()));

        verify(first).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
        verify(second).andExpect(status().isOk());
    }

    @Test
    @DisplayName("재발송 요청: 없는 이메일·PENDING 회원 모두 200, 형식 오류만 400")
    void resendAlways200() throws Exception {
        json("/api/auth/email/resend", "{\"email\":\"nobody@helpnest.local\"}").andExpect(status().isOk());
        json("/api/auth/email/resend", "{\"email\":\"%s\"}".formatted(EMAIL)).andExpect(status().isOk());
        json("/api/auth/email/resend", "{\"email\":\"not-an-email\"}").andExpect(status().isBadRequest());
    }

    private static String token(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private ResultActions verify(String token) throws Exception {
        return json("/api/auth/email/verify", "{\"token\":\"%s\"}".formatted(token));
    }

    private ResultActions login() throws Exception {
        return json("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD));
    }

    private ResultActions json(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
