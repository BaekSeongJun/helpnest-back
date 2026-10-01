// @owner BSJ
package com.helpnest.domain.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.faq.entity.Faq;
import com.helpnest.domain.faq.port.FaqInfo;
import com.helpnest.domain.faq.port.FaqQueryPort;
import com.helpnest.domain.faq.repository.FaqRepository;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.security.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FAQ 공개 조회·조회수·관리 권한·AI 포트 (FR-FAQ-01, 02, docs/04 §4). 시드와 섞이지 않게 질문에 KW 를 넣는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FaqFlowTest {

    private static final String KW = "FT테스트키워드";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    FaqRepository faqRepository;
    @Autowired
    FaqQueryPort faqQueryPort;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    JwtProvider jwtProvider;

    Member lead;
    Member agent;
    Faq published;
    Faq hidden;

    @BeforeEach
    void setUp() {
        lead = memberRepository.save(member("faq-lead@helpnest.local", MemberRole.LEAD));
        agent = memberRepository.save(member("faq-agent@helpnest.local", MemberRole.AGENT));
        published = faqRepository.save(faq(TicketCategory.REFUND, KW + " 환불 공개", true));
        hidden = faqRepository.save(faq(TicketCategory.REFUND, KW + " 환불 비공개", false));
        faqRepository.save(faq(TicketCategory.DELIVERY, KW + " 배송 공개", true));
    }

    @Test
    @DisplayName("공개 목록: 비공개 제외, 유형·키워드(대소문자 무시) 필터")
    void publicList() throws Exception {
        mockMvc.perform(get("/api/faqs").param("category", "REFUND").param("keyword", KW.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].faqId").value(published.getId()));
        mockMvc.perform(get("/api/faqs").param("keyword", KW))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("상세: 조회수 +1, 비공개 글은 404")
    void detailIncrementsViewCount() throws Exception {
        mockMvc.perform(get("/api/faqs/" + published.getId())).andExpect(jsonPath("$.data.viewCount").value(1));
        mockMvc.perform(get("/api/faqs/" + published.getId())).andExpect(jsonPath("$.data.viewCount").value(2));
        mockMvc.perform(get("/api/faqs/" + hidden.getId())).andExpect(status().isNotFound());
        assertThat(faqRepository.findById(hidden.getId()).orElseThrow().getViewCount()).isZero();
    }

    @Test
    @DisplayName("관리: LEAD 는 비공개 포함 목록·작성·수정·삭제, AGENT 는 403")
    void adminCrud() throws Exception {
        String lead = bearer(this.lead);
        mockMvc.perform(get("/api/admin/faqs").param("keyword", KW).header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(jsonPath("$.data.totalElements").value(3));

        String created = mockMvc.perform(post("/api/admin/faqs").header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","question":"%s 결제","answer":"답변"}""".formatted(KW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.published").value(true))
                .andReturn().getResponse().getContentAsString();
        Integer id = com.jayway.jsonpath.JsonPath.read(created, "$.data.faqId");

        mockMvc.perform(put("/api/admin/faqs/" + id).header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","question":"수정","answer":"수정 답변","published":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.published").value(false));
        mockMvc.perform(delete("/api/admin/faqs/" + id).header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk());
        assertThat(faqRepository.existsById(id.longValue())).isFalse();

        mockMvc.perform(post("/api/admin/faqs").header(HttpHeaders.AUTHORIZATION, bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"ETC","question":"q","answer":"a"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/faqs").header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"NOPE","question":"q","answer":"a"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("FaqQueryPort: 공개 글만 유형·키워드로, 모르는 유형은 빈 목록")
    void queryPort() {
        List<FaqInfo> result = faqQueryPort.findPublishedByCategory("REFUND", KW, 5);

        assertThat(result).extracting(FaqInfo::faqId).containsExactly(published.getId());
        assertThat(result.get(0).category()).isEqualTo("REFUND");
        assertThat(faqQueryPort.findPublishedByCategory("NOPE", null, 5)).isEmpty();
        assertThat(faqQueryPort.findPublishedByCategory("REFUND", null, 0)).isEmpty();
    }

    private Faq faq(TicketCategory category, String question, boolean isPublished) {
        return Faq.builder().category(category).question(question).answer("답변 " + question)
                .published(isPublished).createdBy(lead.getId()).build();
    }

    private static Member member(String email, MemberRole role) {
        return Member.builder().email(email).password("x").name("테스트").role(role).build();
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }
}
