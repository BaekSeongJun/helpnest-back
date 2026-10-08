// @owner BSJ
package com.helpnest.domain.dashboard.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.dashboard.agent.AgentDetail.Breakdown;
import com.helpnest.domain.dashboard.agent.AgentDetail.Daily;
import com.helpnest.domain.dashboard.agent.AgentDetail.TeamAverage;
import com.helpnest.domain.dashboard.dto.AgentStat;
import com.helpnest.global.security.JwtProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

/** 상담원 상세 집계를 실 PostgreSQL 에서 검증. DashboardApiTest 처럼 2020-01 구간에 SQL 로 시드한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentDetailApiTest {

    // 집계 구간 [FROM, TO) = 서울 2020-01-10 ~ 2020-01-12
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2020-01-10T00:00:00+09:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2020-01-13T00:00:00+09:00");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AgentDetailQueryRepository repository;

    private Long agent;
    private Long other;

    private Long member(String email) {
        return jdbc.queryForObject("""
                insert into member(email, password, name, role) values (?, 'x', '상세', 'AGENT') returning member_id
                """, Long.class, email);
    }

    private void ticket(String no, Long agentId, String category, String priority, boolean breached, String createdAt,
            Integer firstResponseMin, String resolvedAt) {
        jdbc.update("""
                insert into ticket(ticket_no, guest_email, title, content, category, priority, status, agent_id,
                                   sla_breached, first_response_due_at, created_at, first_responded_at, resolved_at)
                values (?, 'g@example.com', 't', 'c', ?, ?, 'IN_PROGRESS', ?, ?, ?::timestamptz + interval '1 hour',
                        ?::timestamptz, ?::timestamptz + make_interval(mins => ?), ?::timestamptz)
                """, no, category, priority, agentId, breached, createdAt, createdAt, createdAt, firstResponseMin,
                resolvedAt);
    }

    private void survey(String ticketNo, Integer rating, String submittedAt) {
        jdbc.update("""
                insert into survey(ticket_id, token, rating, comment, sent_at, expires_at, submitted_at)
                select ticket_id, 'detail-' || ticket_no, ?, '의견', now(), now() + interval '72 hours', ?::timestamptz
                from ticket where ticket_no = ?
                """, rating, submittedAt, ticketNo);
    }

    @BeforeEach
    void seed() {
        agent = member("detail-a@test.local");
        other = member("detail-b@test.local");
        // 서울 1/10 23:30 접수(UTC 14:30) → 1/10 버킷, 1/11 01:00 해결 → 1/11 버킷 (UTC 날짜로 묶으면 둘 다 틀린다)
        ticket("HN-DTL-1", agent, "REFUND", "HIGH", true, "2020-01-10T14:30:00Z", 30, "2020-01-10T16:00:00Z");
        ticket("HN-DTL-2", agent, "REFUND", "NORMAL", false, "2020-01-12T00:00:00Z", 90, "2020-01-12T03:00:00Z");
        ticket("HN-DTL-3", agent, "DELIVERY", "NORMAL", false, "2020-01-12T01:00:00Z", null, null);
        ticket("HN-DTL-4", agent, "ETC", "NORMAL", false, "2020-01-13T00:00:00Z", null, null); // 구간 밖
        ticket("HN-DTL-5", other, "ETC", "NORMAL", false, "2020-01-11T00:00:00Z", null, null);   // 다른 상담원
        survey("HN-DTL-1", 5, "2020-01-11T00:00:00Z");
        survey("HN-DTL-2", null, null);                    // 미응답 제외
        survey("HN-DTL-5", 1, "2020-01-11T00:00:00Z");     // 다른 상담원 제외
    }

    @Test
    @DisplayName("일별: 서울 날짜로 묶고 빈 날도 0 으로, 구간 밖·다른 상담원 제외")
    void daily() {
        List<Daily> daily = repository.daily(agent, FROM, TO, LocalDate.of(2020, 1, 10), LocalDate.of(2020, 1, 12));

        assertThat(daily).containsExactly(
                new Daily(LocalDate.of(2020, 1, 10), 1, 0, 30.0),
                new Daily(LocalDate.of(2020, 1, 11), 0, 1, null),
                new Daily(LocalDate.of(2020, 1, 12), 2, 1, 90.0));
    }

    @Test
    @DisplayName("분해: 유형·우선순위별 건수·평균 해결·위반율 (많은 순)")
    void breakdown() {
        assertThat(repository.byCategory(agent, FROM, TO)).containsExactly(
                new Breakdown("REFUND", 2, 2.25, 50.0),
                new Breakdown("DELIVERY", 1, null, 0.0));
        assertThat(repository.byPriority(agent, FROM, TO)).containsExactly(
                new Breakdown("NORMAL", 2, 3.0, 0.0),
                new Breakdown("HIGH", 1, 1.5, 100.0));
    }

    @Test
    @DisplayName("목록: 티켓은 최근 순, 설문은 제출된 본인 담당분만")
    void lists() {
        assertThat(repository.tickets(agent, FROM, TO)).extracting(AgentDetail.RecentTicket::ticketNo)
                .containsExactly("HN-DTL-3", "HN-DTL-2", "HN-DTL-1");
        assertThat(repository.surveys(agent, FROM, TO)).singleElement()
                .satisfies(s -> {
                    assertThat(s.ticketNo()).isEqualTo("HN-DTL-1");
                    assertThat(s.rating()).isEqualTo(5);
                    assertThat(s.comment()).isEqualTo("의견");
                });
    }

    @Test
    @DisplayName("팀 평균: 건수는 0건 상담원 포함, 시간·비율·만족도는 값 있는 상담원만, 빈 팀은 null")
    void teamAverage() {
        TeamAverage avg = AgentDetailService.teamAverage(List.of(
                new AgentStat(1L, "a", 3, 1, 2, 30.0, 2.0, 50.0, 4.5),
                new AgentStat(2L, "b", 0, 0, 0, null, null, 0.0, null)));

        assertThat(avg).isEqualTo(new TeamAverage(2, 1.5, 0.5, 1.0, 30.0, 2.0, 25.0, 4.5));
        assertThat(AgentDetailService.teamAverage(List.of()))
                .isEqualTo(new TeamAverage(0, 0, 0, 0, null, null, null, null));
    }

    @Test
    @DisplayName("API: LEAD 200(30D 일별 31행), AGENT 403, 없는 상담원 404")
    void api() throws Exception {
        String lead = "Bearer " + jwtProvider.createAccessToken(other, "LEAD");
        String agentToken = "Bearer " + jwtProvider.createAccessToken(agent, "AGENT");

        mockMvc.perform(get("/api/dashboard/agents/{id}/detail?period=30D", agent)
                        .header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.agentId").value(agent))
                .andExpect(jsonPath("$.data.teamAverage.agentCount").isNumber())
                .andExpect(jsonPath("$.data.daily.length()").value(31));
        mockMvc.perform(get("/api/dashboard/agents/{id}/detail", agent).header(HttpHeaders.AUTHORIZATION, agentToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/agents/{id}/detail", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isNotFound());
    }
}
