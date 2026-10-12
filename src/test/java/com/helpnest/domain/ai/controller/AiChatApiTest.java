// @owner SSJ
package com.helpnest.domain.ai.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.global.security.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /api/ai/chat 권한·검증. 답변·handoff 분기는 AiChatServiceTest.
 * {@code @MockitoBean} 을 쓰지 않는다 — 컨텍스트가 하나 더 캐시되면 Hikari 풀이 PG max_connections 를 넘긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiChatApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    private ResultActions chat(String role, String body) throws Exception {
        return mockMvc.perform(post("/api/ai/chat")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(9001L, role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("회원 고객 → 200, 근거 없는 질문은 handoff")
    void customer() throws Exception {
        chat("CUSTOMER", "{\"question\":\"qzxv-없는-단어\",\"history\":[{\"role\":\"AI\",\"content\":\"안녕하세요\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.handoff").value(true))
                .andExpect(jsonPath("$.data.references").isEmpty());
    }

    @Test
    @DisplayName("비회원·직원 403, 토큰 없음 401")
    void forbidden() throws Exception {
        for (String role : List.of(JwtProvider.GUEST_ROLE, "AGENT", "ADMIN")) {
            chat(role, "{\"question\":\"환불\"}").andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"환불\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("질문 공백·500자 초과·history role 오류 → 400")
    void invalid() throws Exception {
        chat("CUSTOMER", "{\"question\":\" \"}").andExpect(status().isBadRequest());
        chat("CUSTOMER", "{\"question\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest());
        chat("CUSTOMER", "{\"question\":\"환불\",\"history\":[{\"role\":\"SYSTEM\",\"content\":\"x\"}]}")
                .andExpect(status().isBadRequest());
    }
}
