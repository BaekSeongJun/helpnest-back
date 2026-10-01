// @owner BSJ
package com.helpnest.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.infra.mail.PasswordResetMailCommand;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기·재설정 (FR-AUTH-07), 비밀번호 변경·내 정보 수정 (FR-AUTH-08).
 * 테스트 트랜잭션은 커밋되지 않아 메일 리스너(AFTER_COMMIT)는 돌지 않는다 — 발행된 메일 명령으로 링크를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class PasswordFlowTest {

    private static final String EMAIL = "pw-test@helpnest.local";
    private static final String PASSWORD = "Test1234!";
    private static final String NEW_PASSWORD = "NewPass5678!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    EntityManager em;

    @BeforeEach
    void signup() throws Exception {
        json(post("/api/auth/signup"), """
                {"email":"%s","password":"%s","name":"비번테스트","phone":"010-1111-2222"}
                """.formatted(EMAIL, PASSWORD)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("찾기: 가입·미가입 모두 200, 메일은 가입된 이메일에만 (링크는 FRONT_ORIGIN/reset-password?token=)")
    void resetRequestSameResponse() throws Exception {
        json(post("/api/auth/password/reset-request"), "{\"email\":\"nobody@helpnest.local\"}")
                .andExpect(status().isOk());
        assertThat(mails()).isEmpty();

        json(post("/api/auth/password/reset-request"), "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk());
        assertThat(mails()).singleElement().satisfies(m -> {
            assertThat(m.email()).isEqualTo(EMAIL);
            assertThat(m.guest()).isFalse();
            assertThat(m.resetUrl()).startsWith("http://localhost:3000/reset-password?token=");
        });
    }

    @Test
    @DisplayName("재설정: 새 비밀번호로 로그인, 옛 비밀번호·기존 Refresh 무효, 같은 링크 재사용 불가")
    void resetPassword() throws Exception {
        Cookie oldRefresh = login(PASSWORD).getResponse().getCookie("refreshToken");
        String token = requestToken();

        reset(token).andExpect(status().isOk());

        login(NEW_PASSWORD);
        loginRaw(PASSWORD).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(oldRefresh)).andExpect(status().isUnauthorized());
        reset(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("재설정: 만료된 링크·먼저 보낸 다른 링크·빈 토큰은 같은 400")
    void invalidTokens() throws Exception {
        String first = requestToken();
        String second = requestToken();
        reset(second).andExpect(status().isOk());
        reset(first).andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));   // 함께 사용 처리됨

        String expired = requestToken();
        jdbc.update("UPDATE password_reset_token SET expires_at = NOW() - INTERVAL '1 minute' WHERE used_at IS NULL");
        em.clear();   // JDBC 로 바꾼 값을 1차 캐시가 가리지 않게
        reset(expired).andExpect(status().isBadRequest());

        json(post("/api/auth/password/reset"), "{\"token\":\" \",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("변경: 현재 비밀번호 불일치 400, 성공 시 Refresh 전부 폐기")
    void changePassword() throws Exception {
        MvcResult session = login(PASSWORD);
        String bearer = "Bearer " + JsonPath.read(session.getResponse().getContentAsString(), "$.data.accessToken");

        json(patch("/api/members/me/password").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"%s\"}".formatted(NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_PASSWORD_MISMATCH"));
        json(patch("/api/members/me/password").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"short\"}".formatted(PASSWORD))
                .andExpect(status().isBadRequest());

        json(patch("/api/members/me/password").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(PASSWORD, NEW_PASSWORD))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(session.getResponse().getCookie("refreshToken")))
                .andExpect(status().isUnauthorized());
        login(NEW_PASSWORD);
    }

    @Test
    @DisplayName("내 정보 수정: 이름·연락처 변경, 연락처를 비우면 삭제, 로그인 필요")
    void updateProfile() throws Exception {
        String bearer = "Bearer " + JsonPath.read(login(PASSWORD).getResponse().getContentAsString(),
                "$.data.accessToken");

        json(patch("/api/members/me").header(HttpHeaders.AUTHORIZATION, bearer),
                "{\"name\":\" 새이름 \",\"phone\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
        json(patch("/api/members/me").header(HttpHeaders.AUTHORIZATION, bearer), "{\"name\":\"\"}")
                .andExpect(status().isBadRequest());
        json(patch("/api/members/me"), "{\"name\":\"익명\"}").andExpect(status().isUnauthorized());
    }

    private List<PasswordResetMailCommand> mails() {
        return events.stream(PasswordResetMailCommand.class).toList();
    }

    /** 재설정 메일을 요청하고 링크의 토큰을 꺼낸다 */
    private String requestToken() throws Exception {
        json(post("/api/auth/password/reset-request"), "{\"email\":\"%s\"}".formatted(EMAIL))
                .andExpect(status().isOk());
        List<PasswordResetMailCommand> sent = mails();
        String url = sent.get(sent.size() - 1).resetUrl();
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private ResultActions reset(String token) throws Exception {
        return json(post("/api/auth/password/reset"),
                "{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, NEW_PASSWORD));
    }

    private MvcResult login(String password) throws Exception {
        return loginRaw(password).andExpect(status().isOk()).andReturn();
    }

    private ResultActions loginRaw(String password) throws Exception {
        return json(post("/api/auth/login"), "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, password));
    }

    private ResultActions json(
            MockHttpServletRequestBuilder req, String body)
            throws Exception {
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
