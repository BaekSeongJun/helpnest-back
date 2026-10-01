// @owner BSJ
package com.helpnest.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/** 가입 → 로그인 → 내 정보 → refresh 회전 → 재사용 탐지 → 로그아웃 (FR-AUTH-01, 02) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional   // 테스트 데이터는 롤백
class AuthFlowTest {

    private static final String EMAIL = "flow-test@helpnest.local";
    private static final String PASSWORD = "Test1234!";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void signup() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","name":"테스트","phone":"010-0000-0000"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andExpect(cookie().path("refreshToken", "/"))
                .andExpect(cookie().sameSite("refreshToken", "Lax"))
                .andReturn();
    }

    private MvcResult refresh(Cookie cookie) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(cookie)).andReturn();
    }

    private static String accessToken(MvcResult r) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), "$.data.accessToken");
    }

    @Test
    @DisplayName("로그인 → 내 정보 → refresh 회전 → 이전 토큰 재사용 시 전체 폐기")
    void rotationAndReuseDetection() throws Exception {
        MvcResult login = login();
        Cookie first = login.getResponse().getCookie("refreshToken");

        mockMvc.perform(get("/api/members/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(EMAIL));

        // 회전: 새 쿠키·새 Access
        MvcResult rotated = refresh(first);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        Cookie second = rotated.getResponse().getCookie("refreshToken");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(accessToken(rotated)).isNotBlank();

        // 유예(30초)가 지난 뒤 이미 쓴 토큰 재사용 → 거부 + 그 회원의 Refresh 전부 폐기
        ageRotation();
        MvcResult reused = refresh(first);
        assertThat(reused.getResponse().getStatus()).isEqualTo(401);
        assertThat(reused.getResponse().getContentAsString()).contains("AUTH_REFRESH_INVALID");

        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("회전 직후(유예 안) 옛 토큰 재사용은 응답 유실·동시 탭으로 보고 재발급, 다른 토큰은 살아 있다")
    void reuseWithinGraceReissues() throws Exception {
        Cookie first = login().getResponse().getCookie("refreshToken");
        Cookie second = refresh(first).getResponse().getCookie("refreshToken");

        MvcResult again = refresh(first);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(again.getResponse().getCookie("refreshToken").getValue())
                .isNotEqualTo(second.getValue());
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("일괄 폐기(탐지·비밀번호 변경 등)된 토큰은 회전 직후라도 유예 없음")
    void bulkRevokedHasNoGrace() throws Exception {
        Cookie first = login().getResponse().getCookie("refreshToken");
        Cookie second = refresh(first).getResponse().getCookie("refreshToken");
        Long memberId = jdbc.queryForObject("SELECT member_id FROM member WHERE email = ?", Long.class, EMAIL);
        refreshTokenRepository.revokeAllByMemberId(memberId);

        assertThat(refresh(first).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(401);
    }

    /** 이 회원의 회전 시각을 유예 밖(1분 전)으로. 테스트 트랜잭션 하나라 JPA 변경을 먼저 반영하고 캐시를 비운다 */
    private void ageRotation() {
        em.flush();
        jdbc.update("UPDATE refresh_token SET rotated_at = rotated_at - INTERVAL '1 minute' WHERE rotated_at IS NOT NULL "
                + "AND member_id = (SELECT member_id FROM member WHERE email = ?)", EMAIL);
        em.clear();
    }

    @Test
    @DisplayName("로그아웃하면 쿠키가 삭제되고 그 Refresh 는 더 쓸 수 없다")
    void logout() throws Exception {
        MvcResult login = login();
        Cookie cookie = login.getResponse().getCookie("refreshToken");

        mockMvc.perform(post("/api/auth/logout").cookie(cookie)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken(login)))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("refreshToken", 0));

        assertThat(refresh(cookie).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 AUTH_INVALID_CREDENTIALS")
    void wrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-password"}
                                """.formatted(EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("같은 이메일로 다시 가입하면 409")
    void duplicateEmail() throws Exception {
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","name":"중복"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_EMAIL_DUPLICATED"));
    }

    @Test
    @DisplayName("쿠키 없이 refresh 하면 401")
    void refreshWithoutCookie() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REFRESH_INVALID"));
    }
}
