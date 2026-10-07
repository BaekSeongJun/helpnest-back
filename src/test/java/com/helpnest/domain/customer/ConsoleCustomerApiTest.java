// @owner BSJ
package com.helpnest.domain.customer;

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
import java.time.OffsetDateTime;
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
 * 고객 이력 묶음 (FR-HIS-01·02). 회원 한 명과 비회원 한 명(이메일 대소문자 혼용)에게 티켓을 만들어
 * 묶음·요약·현재 티켓 제외·오류를 실제 보안 필터 체인으로 확인한다. 매 테스트가 롤백된다.
 *
 * <pre>
 * 회원(customer1): M1(5점) · M2(3점) · M3(미응답)  → 총 3건, 평균 4.0
 * 비회원(history@example.com): G1(4점, 주소 대문자 혼용) · G2(미응답, 소문자) → 총 2건, 평균 4.0
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("고객 이력 API")
class ConsoleCustomerApiTest {

    private static final String GUEST_EMAIL = "history@example.com";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    SurveyService surveyService;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    MemberRepository memberRepository;

    Long memberId;
    Long memberLastTicketId;
    Long guestFirstTicketId;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        givenMember(5);
        givenMember(3);
        memberLastTicketId = givenMember(null);
        guestFirstTicketId = givenGuest("History@Example.com", 4);
        givenGuest(GUEST_EMAIL, null);
    }

    /** 회원 티켓 + (rating 이 있으면) 설문 응답. 티켓 id 를 돌려준다 */
    private Long givenMember(Integer rating) {
        return save(Ticket.builder().customerId(memberId), rating);
    }

    private Long givenGuest(String email, Integer rating) {
        return save(Ticket.builder().guestName("김비회원").guestEmail(email), rating);
    }

    private Long save(Ticket.TicketBuilder builder, Integer rating) {
        Ticket ticket = builder
                .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .title("이력 테스트")
                .content("본문")
                .channel(TicketChannel.WEB)
                .category(TicketCategory.ETC)
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build();
        ticketRepository.save(ticket);
        String token = surveyService.issueOrReissue(ticket.getId()).getToken();
        if (rating != null) {
            surveyService.submit(token, rating, null);
        }
        return ticket.getId();
    }

    private ResultActions asUser(String email, String url) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"Test1234!\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bearer = "Bearer " + JsonPath.<String>read(body, "$.data.accessToken");
        return mockMvc.perform(get(url).header("Authorization", bearer));
    }

    private DocumentContext ok(ResultActions r) throws Exception {
        return JsonPath.parse(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("회원은 ID 로 묶이고 평균은 응답한 별점만 계산한다")
    void groupsMemberById() throws Exception {
        DocumentContext json = ok(asUser("agent1@helpnest.local", "/api/console/customers/M-" + memberId + "/tickets"));

        assertThat(json.<Integer>read("$.data.summary.totalCount")).isEqualTo(3);
        assertThat(json.<Double>read("$.data.summary.avgRating")).isEqualTo(4.0); // (5+3)/2, 미응답 제외
        assertThat(json.<String>read("$.data.customerName")).isEqualTo("정고객");
        assertThat(json.<Integer>read("$.data.tickets.totalElements")).isEqualTo(3);
        assertThat(json.<Integer>read("$.data.tickets.content[0].ticketId"))
                .isEqualTo(memberLastTicketId.intValue()); // 최근순
        assertThat(json.<Object>read("$.data.tickets.content[0].rating")).isNull();
    }

    @Test
    @DisplayName("비회원은 이메일 대소문자와 무관하게 묶인다")
    void groupsGuestByEmailIgnoringCase() throws Exception {
        DocumentContext json = ok(asUser("agent1@helpnest.local",
                "/api/console/customers/G-" + GUEST_EMAIL.toUpperCase() + "/tickets"));

        assertThat(json.<Integer>read("$.data.summary.totalCount")).isEqualTo(2);
        assertThat(json.<Double>read("$.data.summary.avgRating")).isEqualTo(4.0);
        assertThat(json.<String>read("$.data.customerName")).isEqualTo("김비회원");
    }

    @Test
    @DisplayName("by-ticket 은 현재 티켓을 목록에서 빼고 요약에는 포함한다")
    void byTicketExcludesCurrent() throws Exception {
        DocumentContext json = ok(asUser("agent1@helpnest.local",
                "/api/console/customers/by-ticket/" + guestFirstTicketId));

        assertThat(json.<String>read("$.data.customerKey")).isEqualTo("G-History@Example.com");
        assertThat(json.<Integer>read("$.data.summary.totalCount")).isEqualTo(2);
        assertThat(json.<Integer>read("$.data.tickets.totalElements")).isEqualTo(1);
        assertThat(json.<java.util.List<Integer>>read("$.data.tickets.content[*].ticketId"))
                .doesNotContain(guestFirstTicketId.intValue());
    }

    @Test
    @DisplayName("응답이 하나도 없으면 avgRating 은 null, 티켓이 없으면 0건")
    void emptyCustomer() throws Exception {
        DocumentContext json = ok(asUser("agent1@helpnest.local", "/api/console/customers/M-999999999/tickets"));

        assertThat(json.<Integer>read("$.data.summary.totalCount")).isZero();
        assertThat(json.<Object>read("$.data.summary.avgRating")).isNull();
        assertThat(json.<Object>read("$.data.summary.lastTicketAt")).isNull();
    }

    @Test
    @DisplayName("고객 키 형식이 틀리면 400")
    void invalidKey() throws Exception {
        for (String key : new String[] {"X-1", "M-abc", "M-0", "M-", "G-", "1"}) {
            asUser("agent1@helpnest.local", "/api/console/customers/" + key + "/tickets")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("없는 티켓이면 404")
    void ticketNotFound() throws Exception {
        asUser("agent1@helpnest.local", "/api/console/customers/by-ticket/999999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    @DisplayName("고객 역할은 접근할 수 없다")
    void customerForbidden() throws Exception {
        asUser("customer1@helpnest.local", "/api/console/customers/M-" + memberId + "/tickets")
                .andExpect(status().isForbidden());
    }
}
