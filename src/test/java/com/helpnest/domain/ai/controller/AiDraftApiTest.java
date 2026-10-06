// @owner SSJ
package com.helpnest.domain.ai.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.ai.repository.DraftContextRepository;
import com.helpnest.domain.ai.repository.DraftContextRepository.RecentReply;
import com.helpnest.global.security.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실 PostgreSQL + MockLlmClient 로 초안 API 와 읽기 전용 native 쿼리 확인.
 * 남의 엔티티를 import 하지 않도록 티켓·답변은 SQL 로 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiDraftApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtProvider jwtProvider;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DraftContextRepository contextRepository;

    private Long assigneeId;
    private Long ticketId;

    private Long memberId(String email) {
        return jdbc.queryForObject("select member_id from member where email = ?", Long.class, email);
    }

    private String bearer(String email, String role) {
        return "Bearer " + jwtProvider.createAccessToken(memberId(email), role);
    }

    @BeforeEach
    void givenAssignedTicketWithReplies() {
        assigneeId = memberId("agent1@helpnest.local");
        ticketId = jdbc.queryForObject("""
                insert into ticket(ticket_no, customer_id, title, content, category, status, agent_id,
                                   first_response_due_at)
                values ('HN-TEST-DRAFT', ?, '환불 문의', '환불이 아직 안 됐어요', 'REFUND', 'IN_PROGRESS', ?,
                        now() + interval '1 day')
                returning ticket_id
                """, Long.class, memberId("customer1@helpnest.local"), assigneeId);
        jdbc.update("insert into ticket_reply(ticket_id, writer_type, content, is_internal) values (?, 'AGENT', '확인 중입니다', false)", ticketId);
        jdbc.update("insert into ticket_reply(ticket_id, writer_type, content, is_internal) values (?, 'AGENT', '내부 메모', true)", ticketId);
    }

    @Test
    @DisplayName("담당 AGENT 생성 → 200, 목록에 저장된 초안")
    void createAndList() throws Exception {
        String token = bearer("agent1@helpnest.local", "AGENT");
        String url = "/api/console/tickets/{id}/ai/drafts";

        mockMvc.perform(post(url, ticketId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draftId").isNumber())
                .andExpect(jsonPath("$.data.content").value(containsString("[확인 필요")))
                .andExpect(jsonPath("$.data.model").value("mock"))
                .andExpect(jsonPath("$.data.references").isArray());

        mockMvc.perform(get(url, ticketId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("다른 AGENT → 403 AI_NOT_ASSIGNEE, LEAD → 200")
    void permission() throws Exception {
        String url = "/api/console/tickets/{id}/ai/drafts";

        mockMvc.perform(post(url, ticketId).header(HttpHeaders.AUTHORIZATION, bearer("agent2@helpnest.local", "AGENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AI_NOT_ASSIGNEE"));
        mockMvc.perform(post(url, ticketId).header(HttpHeaders.AUTHORIZATION, bearer("lead@helpnest.local", "LEAD")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("native 쿼리: 담당자 조회, 최근 공개 답변은 내부 메모 제외")
    void contextQueries() {
        assertThat(contextRepository.findTicketAgent(ticketId)).get()
                .extracting(DraftContextRepository.TicketAgent::getAgentId).isEqualTo(assigneeId);
        assertThat(contextRepository.findTicketAgent(-1L)).isEmpty();
        assertThat(contextRepository.findRecentPublicReplies(ticketId, 5)).extracting(RecentReply::getContent)
                .containsExactly("확인 중입니다");
    }
}
