// @owner PMJ
package com.helpnest.domain.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

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

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.global.security.JwtProvider;

/**
 * SLA 정책 관리 API (docs/04 §8). 조회는 LEAD+, 수정은 ADMIN.
 *
 * <p>정책 4행은 마이그레이션이 넣은 공용 설정이라 이 테스트가 값을 바꾼다 —
 * {@code @Transactional} 롤백으로 원래 값이 돌아오는 것에 기대고 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("SLA 정책 관리 API")
class AdminSlaPolicyApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    SlaPolicyRepository slaPolicyRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    JwtProvider jwtProvider;

    Member admin;
    Member lead;
    Member agent;

    @BeforeEach
    void setUp() {
        admin = memberRepository.save(member("sla-admin@helpnest.local", MemberRole.ADMIN));
        lead = memberRepository.save(member("sla-lead@helpnest.local", MemberRole.LEAD));
        agent = memberRepository.save(member("sla-agent@helpnest.local", MemberRole.AGENT));
    }

    @Test
    @DisplayName("목록: 급한 순 4행, 임박 분을 서버가 계산해 내려준다")
    void list() throws Exception {
        mockMvc.perform(get("/api/admin/sla-policies").header(HttpHeaders.AUTHORIZATION, bearer(lead)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].priority").value("URGENT"))
                .andExpect(jsonPath("$.data[0].responseMinutes").value(60))
                // URGENT 60분 × 0.80 = 48분 (PRD 6.1)
                .andExpect(jsonPath("$.data[0].warningMinutes").value(48))
                .andExpect(jsonPath("$.data[3].priority").value("LOW"));
    }

    @Test
    @DisplayName("수정: ADMIN 이 기한·비율을 바꾸면 저장되고 임박 분도 같이 달라진다")
    void update() throws Exception {
        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.URGENT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":30,\"warningRatio\":0.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseMinutes").value(30))
                .andExpect(jsonPath("$.data.warningMinutes").value(15));

        SlaPolicy saved = slaPolicyRepository.findById(TicketPriority.URGENT).orElseThrow();
        assertThat(saved.getResponseMinutes()).isEqualTo(30);
        assertThat(saved.getWarningRatio()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    @DisplayName("수정은 ADMIN 전용: LEAD 는 403, 비로그인은 401 — 조회는 LEAD 도 된다")
    void updateIsAdminOnly() throws Exception {
        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.URGENT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(lead))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":30,\"warningRatio\":0.50}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.URGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":30,\"warningRatio\":0.50}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/sla-policies").header(HttpHeaders.AUTHORIZATION, bearer(agent)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("비율이 DB 정밀도(소수 두 자리)를 넘으면 400 — 조용히 반올림되어 저장되면 안 된다")
    void rejectsTooPreciseRatio() throws Exception {
        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.URGENT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":60,\"warningRatio\":0.805}"))
                .andExpect(status().isBadRequest());

        assertThat(slaPolicyRepository.findById(TicketPriority.URGENT).orElseThrow().getWarningRatio())
                .isEqualByComparingTo(new BigDecimal("0.80"));
    }

    @Test
    @DisplayName("기한 0분·비율 1 초과는 400")
    void rejectsOutOfRange() throws Exception {
        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.HIGH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":0,\"warningRatio\":0.80}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/sla-policies/{priority}", TicketPriority.HIGH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"responseMinutes\":240,\"warningRatio\":1.50}"))
                .andExpect(status().isBadRequest());
    }

    private static Member member(String email, MemberRole role) {
        return Member.builder().email(email).password("x").name("테스트").role(role).build();
    }

    private String bearer(Member member) {
        return "Bearer " + jwtProvider.createAccessToken(member.getId(), member.getRole().name());
    }
}
