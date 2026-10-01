// @owner SSJ
package com.helpnest.domain.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import com.helpnest.domain.ai.entity.TicketAiResult;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ticket.port.TicketClassificationPort;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.domain.ticket.port.TicketSummary;
import com.helpnest.global.security.JwtProvider;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** LLM 은 MockLlmClient(키워드 규칙), 티켓 포트·결과 저장소는 mock */
@SpringBootTest
@AutoConfigureMockMvc
class AiControllerTest {

    private static final String URL = "/api/console/tickets/5/ai";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @MockitoBean
    TicketAiResultRepository repository;

    @MockitoBean
    TicketQueryPort ticketQueryPort;

    @MockitoBean
    TicketClassificationPort ticketClassificationPort;

    private String bearer(String role) {
        return "Bearer " + jwtProvider.createAccessToken(1L, role);
    }

    @Test
    @DisplayName("AGENT 조회 — 결과 있음")
    void getExisting() throws Exception {
        when(repository.findByTicketId(5L)).thenReturn(Optional.of(TicketAiResult.builder().ticketId(5L)
                .category(Category.REFUND).urgency(Urgency.HIGH).sentiment(Sentiment.NEGATIVE)
                .summary("환불 지연 불만").confidence(new BigDecimal("0.86")).status(TicketAiResult.Status.SUCCESS)
                .build()));

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.category").value("REFUND"))
                .andExpect(jsonPath("$.data.urgency").value("HIGH"))
                .andExpect(jsonPath("$.data.sentiment").value("NEGATIVE"))
                .andExpect(jsonPath("$.data.summary").value("환불 지연 불만"))
                .andExpect(jsonPath("$.data.confidence").value(0.86))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("결과 없음(분류 진행 중) → 200 + data null, LEAD 도 허용")
    void getNone() throws Exception {
        when(repository.findByTicketId(5L)).thenReturn(Optional.empty());

        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer("LEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("CUSTOMER 는 403")
    void customerForbidden() throws Exception {
        mockMvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(URL + "/classify").header(HttpHeaders.AUTHORIZATION, bearer("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("재분류 → 분류 후 결과 반환 + 티켓 반영")
    void reclassify() throws Exception {
        when(ticketQueryPort.getTicketSummary(5L)).thenReturn(new TicketSummary("환불 요청", "환불이 안돼서 불만입니다", "ETC"));
        when(repository.findByTicketId(5L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post(URL + "/classify").header(HttpHeaders.AUTHORIZATION, bearer("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.category").value("REFUND"))
                .andExpect(jsonPath("$.data.sentiment").value("NEGATIVE"));
        // Mock 규칙: "안돼" → HIGH, NEGATIVE → URGENT 로 1단계 상향
        verify(ticketClassificationPort).applyClassification(5L, "REFUND", "URGENT", "NEGATIVE");
    }

    @Test
    @DisplayName("재분류 실패해도 200 + status FAILED")
    void reclassifyFailed() throws Exception {
        // 티켓 요약이 null(현재 PMJ 스텁) → 프롬프트 조립 실패 → FAILED
        when(repository.findByTicketId(5L)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post(URL + "/classify").header(HttpHeaders.AUTHORIZATION, bearer("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.category").doesNotExist());
        verify(ticketClassificationPort).applyClassificationFailed(5L);
    }
}
