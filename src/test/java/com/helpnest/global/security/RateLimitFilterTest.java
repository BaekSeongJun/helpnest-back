// @owner BSJ
package com.helpnest.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 필터가 SecurityConfig 에 연결돼 실제 요청을 막는지. 테스트마다 다른 IP 를 써서 서로 영향 없게 */
@SpringBootTest(properties = "app.rate-limit.enabled=true")
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Autowired
    MockMvc mockMvc;

    /** 계정마다 실패 제한(AuthService)도 있어 IP 제한만 보려면 요청마다 이메일을 바꾼다 */
    private static MockHttpServletRequestBuilder login(String ip, String email) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"wrong-password"}
                        """.formatted(email))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
    }

    @Test
    @DisplayName("로그인 IP 10분 10건 초과 → 429 + Retry-After, 다른 IP 는 영향 없음")
    void loginLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("10.0.0.1", "nobody" + i + "@helpnest.local")).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("10.0.0.1", "nobody-last@helpnest.local"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"));

        mockMvc.perform(login("10.0.0.2", "nobody-other@helpnest.local")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비회원 조회(Guest 로그인) IP 10분 10건 초과 → 429 (조회 비밀번호 대입 방지)")
    void guestLoginLimited() throws Exception {
        MockHttpServletRequestBuilder guest = post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ticketNo":"HN-00000000-000000","email":"nobody@example.com","password":"0000"}
                        """)
                .with(r -> {
                    r.setRemoteAddr("10.0.0.4");
                    return r;
                });
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(guest).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(guest).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("비회원 첨부 업로드는 5건 제한, 로그인 사용자(Authorization 헤더)는 제외")
    void attachmentGuestOnly() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(attachment("10.0.0.3", null))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(429));
        }
        mockMvc.perform(attachment("10.0.0.3", null)).andExpect(status().isTooManyRequests());
        // 같은 IP 라도 토큰이 있으면 요청 제한 대상이 아니다 (가짜 토큰이라 인증 단계에서 401)
        mockMvc.perform(attachment("10.0.0.3", "Bearer x")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder attachment(String ip, String authorization) {
        MockHttpServletRequestBuilder req = post("/api/attachments").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        return authorization == null ? req : req.header(HttpHeaders.AUTHORIZATION, authorization);
    }
}
