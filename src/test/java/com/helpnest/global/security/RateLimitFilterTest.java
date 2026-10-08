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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 필터가 SecurityConfig 에 연결돼 실제 요청을 막는지. 테스트마다 다른 IP 를 써서 서로 영향 없게 */
@SpringBootTest(properties = {"app.rate-limit.enabled=true", "app.proxy-secret=" + RateLimitFilterTest.SECRET})
@AutoConfigureMockMvc
class RateLimitFilterTest {

    static final String SECRET = "test-proxy-secret";

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

    @Test
    @DisplayName("위조 X-Forwarded-For 를 매번 바꿔도 CloudFront 가 덧붙인 오른쪽 값으로 세서 막힌다 (back #105)")
    void spoofedXffLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login("10.9.9.9", "spoof" + i + "@helpnest.local")
                            .header("X-Forwarded-For", "1.2.3." + i + ", 203.0.113.7"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login("10.9.9.9", "spoof-last@helpnest.local")
                        .header("X-Forwarded-For", "9.9.9.9, 203.0.113.7"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("clientIp: 직접은 오른쪽 1번째, 비밀 헤더가 맞으면(Amplify 경유) 오른쪽 3번째, XFF 없으면 접속 주소")
    void clientIp() {
        RateLimitFilter filter = new RateLimitFilter(new RateLimiter(), null, SECRET);
        String amplifyChain = "1.2.3.4, 119.71.96.179, 13.124.199.27, 3.34.45.115";   // 10/8 실측(#105)

        assertThat(filter.clientIp(xff("1.2.3.4, 119.71.96.179", null))).isEqualTo("119.71.96.179");
        assertThat(filter.clientIp(xff(amplifyChain, SECRET))).isEqualTo("119.71.96.179");
        assertThat(filter.clientIp(xff("119.71.96.179, 13.124.199.79, 3.34.45.115", SECRET))).isEqualTo("119.71.96.179");
        // 비밀 헤더가 틀리거나 없으면 직접 호출로 본다 → 공격자가 Amplify 경유인 척 왼쪽 값을 고를 수 없다
        assertThat(filter.clientIp(xff(amplifyChain, "guess"))).isEqualTo("3.34.45.115");
        assertThat(filter.clientIp(xff(amplifyChain, null))).isEqualTo("3.34.45.115");
        // 서버에 비밀값이 비어 있으면(로컬) 헤더가 와도 믿지 않는다
        assertThat(new RateLimitFilter(new RateLimiter(), null, "").clientIp(xff(amplifyChain, ""))).isEqualTo("3.34.45.115");
        assertThat(filter.clientIp(xff(null, null))).isEqualTo("10.1.1.1");
    }

    private static MockHttpServletRequest xff(String xff, String proxyHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.1.1");
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        if (proxyHeader != null) {
            req.addHeader(RateLimitFilter.PROXY_HEADER, proxyHeader);
        }
        return req;
    }

    private static MockHttpServletRequestBuilder attachment(String ip, String authorization) {
        MockHttpServletRequestBuilder req = post("/api/attachments").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
        return authorization == null ? req : req.header(HttpHeaders.AUTHORIZATION, authorization);
    }
}
