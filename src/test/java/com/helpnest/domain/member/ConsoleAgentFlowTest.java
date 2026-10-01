// @owner BSJ
package com.helpnest.domain.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.security.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** GET /api/console/agents — 배정 드롭다운용 상담원 목록 (CR #44). 시드와 섞이지 않게 이름에 KW 를 넣는다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ConsoleAgentFlowTest {

    private static final String KW = "CA테스트";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    JdbcTemplate jdbcTemplate;

    Member lead;
    Member customer;
    Member busyAgent;

    @BeforeEach
    void setUp() throws Exception {
        lead = memberRepository.save(member("ca-lead@helpnest.local", KW + " 팀장", MemberRole.LEAD));
        customer = memberRepository.save(member("ca-customer@helpnest.local", KW + " 고객", MemberRole.CUSTOMER));
        busyAgent = memberRepository.save(member("ca-busy@helpnest.local", KW + " 가", MemberRole.AGENT));
        busyAgent.changeAvailable(true);
        memberRepository.save(member("ca-idle@helpnest.local", KW + " 나", MemberRole.AGENT));
        Member inactive = memberRepository.save(member("ca-off@helpnest.local", KW + " 다", MemberRole.AGENT));
        inactive.changeStatus(MemberStatus.INACTIVE);
        memberRepository.flush();

        // 처리 중 2건 + 해결 1건 → activeCount 는 2
        for (String status : List.of("ASSIGNED", "IN_PROGRESS", "RESOLVED")) {
            Long ticketId = createTicket();
            jdbcTemplate.update("UPDATE ticket SET agent_id = ?, status = ? WHERE ticket_id = ?",
                    busyAgent.getId(), status, ticketId);
        }
    }

    @Test
    @DisplayName("LEAD: 활성 상담원만 이름순, 처리 중 건수 포함, 이메일·연락처 없음")
    void leadSeesAgents() throws Exception {
        String body = mockMvc.perform(get("/api/console/agents").header(HttpHeaders.AUTHORIZATION, bearer(lead)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> mine = JsonPath.read(body, "$.data[?(@.name =~ /" + KW + ".*/)]");
        assertThat(mine).extracting(m -> m.get("name")).containsExactly(KW + " 가", KW + " 나");
        assertThat(mine.get(0)).containsEntry("available", true).containsEntry("activeCount", 2);
        assertThat(mine.get(1)).containsEntry("available", false).containsEntry("activeCount", 0);
        assertThat(mine.get(0)).containsOnlyKeys("memberId", "name", "available", "activeCount");
    }

    @Test
    @DisplayName("고객은 403, 비로그인은 401")
    void customerForbidden() throws Exception {
        mockMvc.perform(get("/api/console/agents").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/console/agents")).andExpect(status().isUnauthorized());
    }

    private Long createTicket() throws Exception {
        String res = mockMvc.perform(post("/api/tickets").header(HttpHeaders.AUTHORIZATION, bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"배정 테스트","content":"내용"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(res, "$.data.ticketId")).longValue();
    }

    private static Member member(String email, String name, MemberRole role) {
        return Member.builder().email(email).password("x").name(name).role(role).build();
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }
}
