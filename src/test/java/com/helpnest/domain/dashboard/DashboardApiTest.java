// @owner SSJ
package com.helpnest.domain.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.dashboard.dto.AgentStat;
import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.domain.dashboard.repository.DashboardQueryRepository;
import com.helpnest.domain.dashboard.repository.DashboardQueryRepository.Kpi;
import com.helpnest.global.security.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * 실 PostgreSQL 에서 대시보드 수치 검증 (통합 시나리오 "대시보드 수치가 DB 쿼리 결과와 일치").
 * 기존 시드와 섞이지 않도록 2020-01 구간에 티켓을 넣고 그 구간으로 집계한다. 남의 엔티티 대신 SQL 로 시드.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardApiTest {

    // 집계 구간 [FROM, TO), "오늘" = 2020-01-31 (서울)
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2020-01-01T00:00:00+09:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2020-02-01T00:00:00+09:00");
    private static final OffsetDateTime TODAY = OffsetDateTime.parse("2020-01-31T00:00:00+09:00");

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DashboardQueryRepository repository;

    private Long agentA;
    private Long agentB;
    private long unassignedBefore;

    private Long member(String email, String name) {
        return jdbc.queryForObject("""
                insert into member(email, password, name, role) values (?, 'x', ?, 'AGENT') returning member_id
                """, Long.class, email, name);
    }

    private void ticket(String no, Long agentId, String status, String category, boolean breached, String createdAt,
            Integer firstResponseMin, String resolvedAt) {
        jdbc.update("""
                insert into ticket(ticket_no, guest_email, title, content, category, status, agent_id, sla_breached,
                                   first_response_due_at, created_at, first_responded_at, resolved_at)
                values (?, 'g@example.com', 't', 'c', ?, ?, ?, ?, ?::timestamptz + interval '1 hour', ?::timestamptz,
                        ?::timestamptz + make_interval(mins => ?), ?::timestamptz)
                """, no, category, status, agentId, breached, createdAt, createdAt, createdAt,
                firstResponseMin == null ? null : firstResponseMin, resolvedAt);
    }

    @BeforeEach
    void seed() {
        unassignedBefore = repository.unassigned();
        agentA = member("dash-a@test.local", "가상담");
        agentB = member("dash-b@test.local", "나상담");
        // A: 해결 2건(첫 응답 30·90분, 해결 2·4시간, 1건 오늘 해결·위반) + 처리중 1건(위반)
        ticket("HN-DASH-1", agentA, "RESOLVED", "REFUND", false, "2020-01-10T10:00:00Z", 30, "2020-01-10T12:00:00Z");
        ticket("HN-DASH-2", agentA, "RESOLVED", "REFUND", true, "2020-01-31T01:00:00Z", 90, "2020-01-31T05:00:00Z");
        ticket("HN-DASH-3", agentA, "IN_PROGRESS", "DELIVERY", true, "2020-01-20T00:00:00Z", null, null);
        // B: 구간 내 배정 1건 + 구간 밖(2019-12) 배정 1건 — 현재 담당 수에는 들어가고 기간 지표에는 빠진다
        ticket("HN-DASH-4", agentB, "ASSIGNED", "DELIVERY", false, "2020-01-15T00:00:00Z", null, null);
        ticket("HN-DASH-5", agentB, "ASSIGNED", "ETC", true, "2019-12-15T00:00:00Z", null, null);
        // 미배정 접수 1건
        ticket("HN-DASH-6", null, "RECEIVED", "ETC", false, "2020-01-25T00:00:00Z", null, null);
        // 설문: A 응답 5·4 + 미응답 1(평균 제외), 미배정 티켓 응답 4 → 전체 (5+4+4)/3 = 4.3, A 4.5, B 설문 없음 null
        survey("HN-DASH-1", 5);
        survey("HN-DASH-2", 4);
        survey("HN-DASH-3", null);
        survey("HN-DASH-6", 4);
    }

    // survey(백성준 소유) 는 SQL 로 시드. 미응답은 rating·submitted_at 이 NULL
    private void survey(String ticketNo, Integer rating) {
        jdbc.update("""
                insert into survey(ticket_id, token, rating, sent_at, expires_at, submitted_at)
                select ticket_id, 'dash-' || ticket_no, ?, now(), now() + interval '72 hours',
                       case when ?::smallint is null then null else now() end
                from ticket where ticket_no = ?
                """, rating, rating, ticketNo);
    }

    @Test
    @DisplayName("KPI·분포: 구간 내 접수 5건, SLA 위반 40%, 평균 첫 응답 60분, 만족도 4.3, 미배정은 현재 기준 +1")
    void summaryNumbers() {
        Kpi kpi = repository.kpi(FROM, TO);

        assertThat(kpi.total()).isEqualTo(5);
        assertThat(kpi.slaBreachRate()).isEqualTo(40.0);
        assertThat(kpi.avgFirstResponseMin()).isEqualTo(60.0);
        assertThat(kpi.avgRating()).isEqualTo(4.3);
        assertThat(repository.unassigned()).isEqualTo(unassignedBefore + 1);
        assertThat(repository.countByStatus(FROM, TO))
                .containsExactlyInAnyOrderEntriesOf(Map.of("RESOLVED", 2L, "IN_PROGRESS", 1L, "ASSIGNED", 1L, "RECEIVED", 1L));
        assertThat(repository.countByCategory(FROM, TO))
                .containsExactlyInAnyOrderEntriesOf(Map.of("REFUND", 2L, "DELIVERY", 2L, "ETC", 1L));
    }

    @Test
    @DisplayName("상담원별: 현재 담당·오늘 해결·평균·위반율·만족도(미응답 제외), 대상 없으면 null")
    void agentNumbers() {
        List<AgentStat> stats = repository.agents(FROM, TO, TODAY);

        assertThat(stats).filteredOn(s -> s.agentId().equals(agentA)).singleElement()
                .isEqualTo(new AgentStat(agentA, "가상담", 0, 1, 1, 60.0, 3.0, 66.7, 4.5));
        assertThat(stats).filteredOn(s -> s.agentId().equals(agentB)).singleElement()
                .isEqualTo(new AgentStat(agentB, "나상담", 2, 0, 0, null, null, 0.0, null));
    }

    @Test
    @DisplayName("구간 경계: TO 이후·FROM 이전 접수는 제외")
    void periodBoundary() {
        assertThat(repository.kpi(TODAY, TO).total()).isEqualTo(1); // HN-DASH-2 만
        assertThat(repository.kpi(FROM.minusYears(10), FROM).total()).isGreaterThanOrEqualTo(1); // HN-DASH-5
        assertThat(repository.agent(agentB, FROM, TO, TODAY).slaBreachRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Period: TODAY 는 서울 기준 0시")
    void periodStart() {
        OffsetDateTime now = OffsetDateTime.parse("2020-01-31T16:00:00Z"); // 서울 2020-02-01 01:00
        assertThat(Period.TODAY.start(now)).isEqualTo(OffsetDateTime.parse("2020-02-01T00:00:00+09:00"));
        assertThat(Period.parse("7D").start(now)).isEqualTo(now.minusDays(7));
    }

    @Test
    @DisplayName("상담원 CSV: BOM·헤더·쉼표/따옴표 이름 이스케이프·파일명, AGENT 403")
    void exportAgents() throws Exception {
        member("dash-c@test.local", "김,\"팀\"장");
        String agent = "Bearer " + jwtProvider.createAccessToken(agentA, "AGENT");
        String lead = "Bearer " + jwtProvider.createAccessToken(agentB, "LEAD");

        byte[] body = mockMvc.perform(get("/api/dashboard/agents/export?period=30D")
                        .header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        matchesPattern(".*filename=\"helpnest_agents_30D_\\d{8}\\.csv\".*")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(body, 3, body.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith("상담원,배정,처리중,오늘 해결,평균 첫 응답(분),평균 해결(시간),SLA 위반율(%),평균 만족도\r\n")
                .contains("\r\n\"김,\"\"팀\"\"장\",0,0,0,,,,\r\n");
        mockMvc.perform(get("/api/dashboard/agents/export").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("권한: AGENT 는 /summary·/agents 403, /agents/me 는 본인 행, 잘못된 period 400")
    void permissions() throws Exception {
        String agent = "Bearer " + jwtProvider.createAccessToken(agentA, "AGENT");
        String lead = "Bearer " + jwtProvider.createAccessToken(agentB, "LEAD");

        mockMvc.perform(get("/api/dashboard/summary").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/agents").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/dashboard/agents/me?period=30D").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentId").value(agentA))
                .andExpect(jsonPath("$.data.inProgressCount").value(1));
        mockMvc.perform(get("/api/dashboard/summary?period=7D").header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber());
        mockMvc.perform(get("/api/dashboard/agents?period=1Y").header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isBadRequest());
    }
}
