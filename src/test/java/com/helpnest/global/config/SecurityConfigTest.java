// @owner BSJ
package com.helpnest.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.global.security.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    private String bearer(String role) {
        return "Bearer " + jwtProvider.createAccessToken(1L, role);
    }

    @Test
    @DisplayName("토큰 없이 보호 API 를 호출하면 401 + ApiResponse 형식")
    void noToken() throws Exception {
        mockMvc.perform(get("/api/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("위조 토큰은 401")
    void invalidToken() throws Exception {
        mockMvc.perform(get("/api/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("공개 경로는 토큰 없이 200")
    void publicPath() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AGENT 가 /api/admin 을 호출하면 403")
    void agentToAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/members").header(HttpHeaders.AUTHORIZATION, bearer("AGENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    @DisplayName("역할 계층: ADMIN 은 AGENT 경로를 통과한다 (컨트롤러가 없어 404)")
    void roleHierarchy() throws Exception {
        mockMvc.perform(get("/api/console/tickets").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CUSTOMER 는 /api/console 에 403")
    void customerToConsole() throws Exception {
        mockMvc.perform(get("/api/console/tickets").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CORS 는 /api/attachments 만 FRONT_ORIGIN 허용")
    void cors() throws Exception {
        mockMvc.perform(options("/api/attachments")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));

        mockMvc.perform(options("/api/faqs")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
