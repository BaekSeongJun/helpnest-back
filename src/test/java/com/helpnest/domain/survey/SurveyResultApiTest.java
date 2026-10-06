// @owner BSJ
package com.helpnest.domain.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 결과 목록·요약 (FR-SRV-05, CS-06). 시드 상담원 두 명(agent1·agent3)에게 티켓을 나눠 주고
 * 설문을 발송·응답시킨 뒤 권한·필터·집계를 실제 보안 필터 체인으로 확인한다. 매 테스트가 롤백된다.
 *
 * <pre>
 * agent1: A(DELIVERY, 5점 "빨라요") · B(REFUND, 3점) · C(DELIVERY, 미응답)
 * agent3: D(REFUND, 1점 "느려요")
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("설문 결과 API — 목록·요약")
class SurveyResultApiTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String LIST = "/api/console/surveys";
    private static final String SUMMARY = "/api/console/surveys/summary";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    SurveyService surveyService;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    MemberRepository memberRepository;

    Long agent1;
    Long agent3;
    String ticketNoA;
    String ticketNoD;

    @BeforeEach
    void setUp() {
        agent1 = memberRepository.findByEmail("agent1@helpnest.local").orElseThrow().getId();
        agent3 = memberRepository.findByEmail("agent3@helpnest.local").orElseThrow().getId();
        ticketNoA = given(agent1, TicketCategory.DELIVERY, 5, "빨라요");
        given(agent1, TicketCategory.REFUND, 3, null);
        given(agent1, TicketCategory.DELIVERY, null, null);
        ticketNoD = given(agent3, TicketCategory.REFUND, 1, "느려요");
    }

    /** 티켓·설문을 만든다. rating 이 null 이면 발송만 하고 응답은 없다 */
    private String given(Long agentId, TicketCategory category, Integer rating, String comment) {
        Ticket ticket = Ticket.builder()
                .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .title("결과 테스트")
                .content("본문")
                .channel(TicketChannel.WEB)
                .category(category)
                .guestName("김비회원")
                .guestEmail("result@example.com")
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build();
        ticket.assignTo(agentId, OffsetDateTime.now());
        ticketRepository.save(ticket);
        String token = surveyService.issueOrReissue(ticket.getId()).getToken();
        if (rating != null) {
            surveyService.submit(token, rating, comment);
        }
        return ticket.getTicketNo();
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Test1234!\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
    }

    private ResultActions asUser(String email, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", login(email)));
    }

    private DocumentContext ok(ResultActions r) throws Exception {
        return JsonPath.parse(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static long num(DocumentContext json, String path) {
        return json.read(path, Number.class).longValue();
    }

    @Test
    @DisplayName("LEAD 는 전체 응답을 최근 순으로 보고, 필드가 채워진다")
    void leadSeesAll() throws Exception {
        DocumentContext page = ok(asUser("lead@helpnest.local", LIST));

        assertThat(num(page, "$.data.totalElements")).isGreaterThanOrEqualTo(3); // 미응답 C 는 제외
        assertThat(page.<String>read("$.data.content[0].ticketNo")).isEqualTo(ticketNoD); // 마지막 응답이 먼저
        assertThat(page.<String>read("$.data.content[0].customerName")).isEqualTo("김비회원");
        assertThat(page.<String>read("$.data.content[0].agentName")).isNotBlank();
        assertThat(num(page, "$.data.content[0].rating")).isEqualTo(1);
        assertThat(page.<String>read("$.data.content[0].comment")).isEqualTo("느려요");
    }

    @Test
    @DisplayName("AGENT 는 요청한 agentId 와 무관하게 본인 담당분만 본다")
    void agentForcedToSelf() throws Exception {
        DocumentContext page = ok(asUser("agent1@helpnest.local", LIST + "?agentId=" + agent3));

        assertThat(num(page, "$.data.totalElements")).isEqualTo(2);
        assertThat(page.<java.util.List<String>>read("$.data.content[*].ticketNo"))
                .contains(ticketNoA)
                .doesNotContain(ticketNoD);

        DocumentContext summary = ok(asUser("agent1@helpnest.local", SUMMARY + "?agentId=" + agent3));
        assertThat(num(summary, "$.data.sent")).isEqualTo(3); // agent3 의 1건이 섞이지 않는다
    }

    @Test
    @DisplayName("LEAD 는 agentId 로 좁힐 수 있다")
    void leadFiltersByAgent() throws Exception {
        DocumentContext page = ok(asUser("lead@helpnest.local", LIST + "?agentId=" + agent3));

        assertThat(num(page, "$.data.totalElements")).isEqualTo(1);
        assertThat(page.<String>read("$.data.content[0].ticketNo")).isEqualTo(ticketNoD);
    }

    @Test
    @DisplayName("별점·유형·기간 필터")
    void filters() throws Exception {
        String lead = "lead@helpnest.local";
        String agent = "&agentId=" + agent1;
        LocalDate today = LocalDate.now(SEOUL);

        assertThat(num(ok(asUser(lead, LIST + "?rating=5" + agent)), "$.data.totalElements")).isEqualTo(1);
        assertThat(num(ok(asUser(lead, LIST + "?category=REFUND" + agent)), "$.data.totalElements")).isEqualTo(1);
        assertThat(num(ok(asUser(lead, LIST + "?from=%s&to=%s%s".formatted(today, today, agent))),
                "$.data.totalElements")).isEqualTo(2); // 종료일 당일 포함
        assertThat(num(ok(asUser(lead, LIST + "?from=%s%s".formatted(today.plusDays(1), agent))),
                "$.data.totalElements")).isZero();
        assertThat(num(ok(asUser(lead, LIST + "?to=%s%s".formatted(today.minusDays(1), agent))),
                "$.data.totalElements")).isZero();
    }

    @Test
    @DisplayName("요약 — 발송·응답·응답률·평균·분포")
    void summary() throws Exception {
        DocumentContext s = ok(asUser("agent1@helpnest.local", SUMMARY));

        assertThat(num(s, "$.data.sent")).isEqualTo(3);
        assertThat(num(s, "$.data.responded")).isEqualTo(2);
        assertThat(s.read("$.data.responseRate", Double.class)).isEqualTo(66.7);
        assertThat(s.read("$.data.avgRating", Double.class)).isEqualTo(4.0);
        assertThat(num(s, "$.data.distribution['5']")).isEqualTo(1);
        assertThat(num(s, "$.data.distribution['3']")).isEqualTo(1);
        assertThat(num(s, "$.data.distribution['1']")).isZero();
    }

    @Test
    @DisplayName("요약은 별점 필터를 무시한다 (분포가 곧 별점 축)")
    void summaryIgnoresRating() throws Exception {
        DocumentContext s = ok(asUser("agent1@helpnest.local", SUMMARY + "?rating=5"));

        assertThat(num(s, "$.data.responded")).isEqualTo(2);
    }

    @Test
    @DisplayName("대상이 없으면 비율·평균은 null, 분포는 0")
    void emptySummary() throws Exception {
        String future = LocalDate.now(SEOUL).plusDays(30).toString();

        DocumentContext s = ok(asUser("lead@helpnest.local", SUMMARY + "?from=" + future));

        assertThat(num(s, "$.data.sent")).isZero();
        assertThat(s.<Object>read("$.data.responseRate")).isNull();
        assertThat(s.<Object>read("$.data.avgRating")).isNull();
        assertThat(num(s, "$.data.distribution['1']")).isZero();
        assertThat(s.<java.util.Map<String, Object>>read("$.data.distribution")).hasSize(5);
    }

    @Test
    @DisplayName("잘못된 별점·기간·유형·날짜는 400")
    void invalidParams() throws Exception {
        asUser("lead@helpnest.local", LIST + "?rating=9").andExpect(status().isBadRequest());
        asUser("lead@helpnest.local", LIST + "?from=2026-10-10&to=2026-10-01").andExpect(status().isBadRequest());
        asUser("lead@helpnest.local", LIST + "?category=NOPE").andExpect(status().isBadRequest());
        asUser("lead@helpnest.local", SUMMARY + "?from=not-a-date").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비로그인은 401, 고객은 403")
    void accessControl() throws Exception {
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(SUMMARY)).andExpect(status().isUnauthorized());
        asUser("customer1@helpnest.local", LIST).andExpect(status().isForbidden());
        asUser("customer1@helpnest.local", SUMMARY).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }
}
