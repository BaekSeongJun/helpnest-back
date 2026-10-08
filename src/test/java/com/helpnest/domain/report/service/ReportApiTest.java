// @owner SSJ
package com.helpnest.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.report.dto.MonthlyReport;
import com.helpnest.domain.report.dto.MonthlyReport.CategoryRow;
import com.helpnest.domain.report.repository.ReportQueryRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
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
 * 실 PostgreSQL 에서 월간 리포트 수치 검증. 기존 시드와 섞이지 않도록 2019-12·2020-01 에 티켓을 넣는다.
 * 남의 엔티티 대신 SQL 로 시드 (DashboardApiTest 와 같은 방식).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReportService reportService;
    @Autowired
    ReportQueryRepository repository;

    private void ticket(String no, String category, String sentiment, boolean breached, String createdAt,
            Integer firstResponseMin, Integer resolveHour) {
        jdbc.update("""
                insert into ticket(ticket_no, guest_email, title, content, category, sentiment, status, sla_breached,
                                   first_response_due_at, created_at, first_responded_at, resolved_at)
                values (?, 'g@example.com', 't', 'c', ?, ?, 'RECEIVED', ?, ?::timestamptz + interval '1 hour',
                        ?::timestamptz, ?::timestamptz + make_interval(mins => ?),
                        ?::timestamptz + make_interval(hours => ?))
                """, no, category, sentiment, breached, createdAt, createdAt, createdAt, firstResponseMin, createdAt,
                resolveHour);
    }

    @BeforeEach
    void seed() {
        // 2020-01: 환불 2(불만 1, 해결 2·4시간), 배송 1(위반, 첫 응답 30분) + 경계: 서울 1/31 23:59 → 1월
        ticket("HN-RPT-1", "REFUND", "NEGATIVE", false, "2020-01-05T00:00:00+09:00", 10, 2);
        ticket("HN-RPT-2", "REFUND", "NEUTRAL", false, "2020-01-06T00:00:00+09:00", 50, 4);
        ticket("HN-RPT-3", "DELIVERY", null, true, "2020-01-07T00:00:00+09:00", 30, null);
        ticket("HN-RPT-4", "DELIVERY", null, false, "2020-01-31T23:59:00+09:00", null, null);
        // 서울 2/1 00:00 → 2월 (1월 집계에서 제외)
        ticket("HN-RPT-5", "REFUND", null, false, "2020-02-01T00:00:00+09:00", null, null);
        // 2019-12: 환불 1, 계정 2 (계정은 1월에 없음 → 전월 전용 행)
        ticket("HN-RPT-6", "REFUND", null, false, "2019-12-10T00:00:00+09:00", null, null);
        ticket("HN-RPT-7", "ACCOUNT", null, false, "2019-12-11T00:00:00+09:00", null, null);
        ticket("HN-RPT-8", "ACCOUNT", null, false, "2019-12-12T00:00:00+09:00", null, null);
        // 설문: 1월 응답 5·2 + 미응답 1 → 3.5, 2월 티켓 응답 1 은 1월 평균에서 제외
        survey("HN-RPT-1", 5);
        survey("HN-RPT-2", 2);
        survey("HN-RPT-3", null);
        survey("HN-RPT-5", 1);
    }

    // survey(백성준 소유) 는 SQL 로 시드. 미응답은 rating·submitted_at 이 NULL
    private void survey(String ticketNo, Integer rating) {
        jdbc.update("""
                insert into survey(ticket_id, token, rating, sent_at, expires_at, submitted_at)
                select ticket_id, 'rpt-' || ticket_no, ?, now(), now() + interval '72 hours',
                       case when ?::smallint is null then null else now() end
                from ticket where ticket_no = ?
                """, rating, rating, ticketNo);
    }

    @Test
    @DisplayName("2020-01 수치: 합계·전월·위반율·불만 비율, 유형별 전월 대비, 전월 전용 유형 행")
    void monthlyNumbers() {
        MonthlyReport r = reportService.monthly("2020-01");

        assertThat(r.month()).isEqualTo("2020-01");
        assertThat(r.total()).isEqualTo(4);
        assertThat(r.prevTotal()).isEqualTo(3);
        assertThat(r.avgFirstResponseMin()).isEqualTo(30.0);
        assertThat(r.avgResolveHour()).isEqualTo(3.0);
        assertThat(r.slaBreachRate()).isEqualTo(25.0);
        assertThat(r.negativeRate()).isEqualTo(25.0);
        assertThat(r.avgRating()).isEqualTo(3.5);
        assertThat(r.byCategory()).containsExactly(
                new CategoryRow("DELIVERY", 2, 0, null, 0.0),
                new CategoryRow("REFUND", 2, 1, 3.0, 50.0),
                new CategoryRow("ACCOUNT", 0, 2, null, null));
    }

    @Test
    @DisplayName("빈 달: total 0, 비율·평균 null, 유형 행 없음")
    void emptyMonth() {
        MonthlyReport r = reportService.monthly("1999-01");

        assertThat(r.total()).isZero();
        assertThat(r.slaBreachRate()).isNull();
        assertThat(r.negativeRate()).isNull();
        assertThat(r.avgResolveHour()).isNull();
        assertThat(r.avgRating()).isNull();
        assertThat(r.byCategory()).isEmpty();
    }

    @Test
    @DisplayName("month 생략 = 서울 기준 이번 달, 형식 오류 400")
    void parseMonth() {
        // UTC 1/31 15:30 = 서울 2/1 00:30
        Clock clock = Clock.fixed(Instant.parse("2020-01-31T15:30:00Z"), ZoneOffset.UTC);
        ReportService service = new ReportService(repository, clock);

        assertThat(service.parse(null)).isEqualTo(YearMonth.of(2020, 2));
        assertThat(service.parse("2020-12")).isEqualTo(YearMonth.of(2020, 12));
        assertThatThrownBy(() -> service.parse("2020-13")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.parse("202001")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("CSV: BOM·파일명·유형별 행(한글 라벨·증감률)·합계 행, AGENT 403")
    void export() throws Exception {
        String agent = "Bearer " + jwtProvider.createAccessToken(1L, "AGENT");
        String lead = "Bearer " + jwtProvider.createAccessToken(1L, "LEAD");

        byte[] body = mockMvc.perform(get("/api/reports/monthly/export?month=2020-01")
                        .header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename=\"helpnest_report_2020-01.csv\"")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(Arrays.copyOf(body, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(new String(body, 3, body.length - 3, StandardCharsets.UTF_8)).isEqualTo("""
                유형,건수,전월 건수,증감률(%),평균 처리시간(시간),불만 비율(%)\r
                배송,2,0,,,0.0\r
                환불,2,1,100.0,3.0,50.0\r
                계정,0,2,-100.0,,\r
                합계,4,3,33.3,3.0,25.0\r
                """);
        mockMvc.perform(get("/api/reports/monthly/export").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("권한: AGENT 403, LEAD 200, 잘못된 month 400")
    void permissions() throws Exception {
        String agent = "Bearer " + jwtProvider.createAccessToken(1L, "AGENT");
        String lead = "Bearer " + jwtProvider.createAccessToken(1L, "LEAD");

        mockMvc.perform(get("/api/reports/monthly").header(HttpHeaders.AUTHORIZATION, agent))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/monthly?month=2020-01").header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.byCategory.length()").value(3));
        mockMvc.perform(get("/api/reports/monthly?month=2020-1").header(HttpHeaders.AUTHORIZATION, lead))
                .andExpect(status().isBadRequest());
    }
}
