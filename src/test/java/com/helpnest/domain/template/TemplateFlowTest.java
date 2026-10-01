// @owner BSJ
package com.helpnest.domain.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.template.entity.Template;
import com.helpnest.domain.template.repository.TemplateRepository;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.security.JwtProvider;
import com.jayway.jsonpath.JsonPath;
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

/** 템플릿 조회(AGENT+)·관리(LEAD+) (FR-TPL-01, 02, docs/04 §4). 시드와 섞이지 않게 제목에 KW 를 넣는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TemplateFlowTest {

    private static final String KW = "TT테스트키워드";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TemplateRepository templateRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    JwtProvider jwtProvider;

    Member lead;
    Member agent;
    Member customer;
    Template active;
    Template inactive;

    @BeforeEach
    void setUp() {
        lead = memberRepository.save(member("tpl-lead@helpnest.local", MemberRole.LEAD));
        agent = memberRepository.save(member("tpl-agent@helpnest.local", MemberRole.AGENT));
        customer = memberRepository.save(member("tpl-customer@helpnest.local", MemberRole.CUSTOMER));
        active = templateRepository.save(template(TicketCategory.REFUND, KW + " 환불 사용", true));
        inactive = templateRepository.save(template(TicketCategory.REFUND, KW + " 환불 미사용", false));
        templateRepository.save(template(TicketCategory.DELIVERY, KW + " 배송 사용", true));
    }

    @Test
    @DisplayName("상담원 목록: 사용 중만, 유형·키워드(대소문자 무시) 필터. 고객·비로그인은 403·401")
    void agentList() throws Exception {
        mockMvc.perform(get("/api/templates").param("category", "REFUND").param("keyword", KW.toLowerCase())
                        .header(HttpHeaders.AUTHORIZATION, bearer(agent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].templateId").value(active.getId()));
        mockMvc.perform(get("/api/templates").param("keyword", KW).header(HttpHeaders.AUTHORIZATION, bearer(agent)))
                .andExpect(jsonPath("$.data.totalElements").value(2));

        mockMvc.perform(get("/api/templates").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/templates")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리: LEAD 는 미사용 포함 목록·작성·수정·삭제, AGENT 는 403, 잘못된 유형 400")
    void adminCrud() throws Exception {
        String lead = bearer(this.lead);
        mockMvc.perform(get("/api/admin/templates").param("keyword", KW).header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(jsonPath("$.data.totalElements").value(3));

        String created = mockMvc.perform(post("/api/admin/templates").header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","title":"%s 결제","content":"{고객명}님, {티켓번호} 확인했습니다."}"""
                                .formatted(KW)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.content").value("{고객명}님, {티켓번호} 확인했습니다."))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(created, "$.data.templateId");

        mockMvc.perform(put("/api/admin/templates/" + id).header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"PAYMENT","title":"수정","content":"수정 본문","active":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        mockMvc.perform(delete("/api/admin/templates/" + id).header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk());
        assertThat(templateRepository.existsById(id.longValue())).isFalse();

        mockMvc.perform(post("/api/admin/templates").header(HttpHeaders.AUTHORIZATION, bearer(agent))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"ETC","title":"t","content":"c"}"""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/templates").header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"NOPE","title":"t","content":"c"}"""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/admin/templates/" + Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, lead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"ETC","title":"t","content":"c"}"""))
                .andExpect(status().isNotFound());
    }

    private Template template(TicketCategory category, String title, boolean isActive) {
        return Template.builder().category(category).title(title).content("본문 " + title)
                .active(isActive).createdBy(lead.getId()).build();
    }

    private static Member member(String email, MemberRole role) {
        return Member.builder().email(email).password("x").name("테스트").role(role).build();
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }
}
