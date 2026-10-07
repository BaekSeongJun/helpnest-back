// @owner BSJ
package com.helpnest.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.auth.entity.RefreshToken;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.event.AgentAvailabilityChangedEvent;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.security.JwtProvider;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 계정 관리 + 상담 가능 토글 (AD-01, docs/04 §2) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class AdminMemberFlowTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    RefreshTokenRepository refreshTokenRepository;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    ApplicationEvents events;

    Member admin;
    Member lead;
    Member agent;
    Member customer;

    @BeforeEach
    void setUp() {
        admin = save("am-admin@helpnest.local", MemberRole.ADMIN);
        lead = save("am-lead@helpnest.local", MemberRole.LEAD);
        agent = save("am-agent@helpnest.local", MemberRole.AGENT);
        customer = save("am-customer@helpnest.local", MemberRole.CUSTOMER);
    }

    @Test
    @DisplayName("목록·생성은 ADMIN 만 (LEAD 는 /api/admin/** 통과해도 403)")
    void adminOnly() throws Exception {
        mockMvc.perform(get("/api/admin/members").param("role", "AGENT").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.email == 'am-agent@helpnest.local')]").exists())
                .andExpect(jsonPath("$.data.content[?(@.role != 'AGENT')]").isEmpty());
        mockMvc.perform(get("/api/admin/members").header(HttpHeaders.AUTHORIZATION, bearer(lead)))
                .andExpect(status().isForbidden());

        create(admin, "LEAD").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.role").value("LEAD"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
        create(admin, "CUSTOMER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ROLE_NOT_ALLOWED"));
        create(lead, "AGENT").andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비활성·역할 변경 시 Refresh 전부 폐기, 고객↔직원 전환 금지")
    void updateRevokesRefresh() throws Exception {
        RefreshToken token = refreshTokenRepository.save(
                new RefreshToken(agent.getId(), "hash-am-agent", OffsetDateTime.now().plusDays(1)));

        update(admin, agent, """
                {"status":"INACTIVE"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
        assertThat(refreshTokenRepository.findById(token.getId()).orElseThrow().isRevoked()).isTrue();

        update(admin, customer, """
                {"role":"AGENT"}""")
                .andExpect(jsonPath("$.error.code").value("MEMBER_ROLE_NOT_ALLOWED"));
        update(admin, lead, """
                {"role":"AGENT"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("AGENT"));
    }

    @Test
    @DisplayName("본인 ADMIN 강등·비활성 금지")
    void selfChangeForbidden() throws Exception {
        update(admin, admin, """
                {"role":"LEAD"}""")
                .andExpect(jsonPath("$.error.code").value("MEMBER_SELF_CHANGE_FORBIDDEN"));
        update(admin, admin, """
                {"status":"INACTIVE"}""")
                .andExpect(jsonPath("$.error.code").value("MEMBER_SELF_CHANGE_FORBIDDEN"));
    }

    @Test
    @DisplayName("상담 가능 토글: AGENT 만, LEAD·고객은 403")
    void availability() throws Exception {
        availability(agent, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
        availability(lead, true).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_AGENT"));
        availability(customer, true).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("상담 가능 값이 실제로 바뀔 때만 이벤트를 발행한다 (CR #90)")
    void availabilityEvent() throws Exception {
        availability(agent, true).andExpect(status().isOk());
        availability(agent, true).andExpect(status().isOk());   // 같은 값 → 발행 안 함
        availability(agent, false).andExpect(status().isOk());
        availability(lead, true).andExpect(status().isForbidden());   // 거부 → 발행 안 함

        assertThat(events.stream(AgentAvailabilityChangedEvent.class))
                .containsExactly(new AgentAvailabilityChangedEvent(agent.getId(), true),
                        new AgentAvailabilityChangedEvent(agent.getId(), false));
    }

    private ResultActions create(Member by, String role) throws Exception {
        return mockMvc.perform(post("/api/admin/members").header(HttpHeaders.AUTHORIZATION, bearer(by))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"am-new-%s@helpnest.local","password":"Test1234!","name":"신규","role":"%s"}
                        """.formatted(role.toLowerCase(), role)));
    }

    private ResultActions update(Member by, Member target, String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/members/" + target.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(by))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions availability(Member by, boolean available) throws Exception {
        return mockMvc.perform(patch("/api/members/me/availability")
                .header(HttpHeaders.AUTHORIZATION, bearer(by))
                .contentType(MediaType.APPLICATION_JSON).content("{\"available\":" + available + "}"));
    }

    private Member save(String email, MemberRole role) {
        return memberRepository.save(Member.builder().email(email).password("x").name("테스트").role(role).build());
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }
}
