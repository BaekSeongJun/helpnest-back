// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.security.JwtProvider;

/**
 * POST /api/tickets 접수 통합 테스트 (docs/04 §7, FR-INQ-01~03).
 *
 * <p>요청 제한 카운터가 서비스 메모리에 쌓이고 RateLimiter 는 싱글턴이므로 테스트마다 다른
 * 이메일을 쓴다. 같은 이메일을 재사용하면 앞선 테스트가 센 횟수 때문에 뒤의 테스트가 429 를
 * 받는다(테스트 트랜잭션은 롤백되지만 메모리 카운터는 롤백되지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("POST /api/tickets — 문의 접수")
class TicketCreateApiTest {

    /** 티켓번호 형식 (docs/03 §3.2 ticket_no 주석). 6자리를 넘으면 자리수가 늘어난다 */
    private static final String TICKET_NO_PATTERN = "^HN-\\d{8}-\\d{6,}$";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketHistoryRepository ticketHistoryRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JwtProvider jwtProvider;

    private Long customerId;
    private String customerToken;

    @BeforeEach
    void loginAsSeedCustomer() {
        customerId = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        customerToken = jwtProvider.createAccessToken(customerId, "CUSTOMER");
    }

    private MockHttpServletRequestBuilder createRequest(String body) {
        return post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken);
    }

    /** 비회원 접수 본문. categoryHint 가 null 이면 유형을 고르지 않은 경우다 */
    private static String guestBody(String email, String categoryHint) {
        String hint = categoryHint == null ? "" : "\"categoryHint\":\"" + categoryHint + "\",";
        return "{\"title\":\"주문한 상품이 아직 안 왔어요\","
                + "\"content\":\"배송 조회가 안 됩니다.\","
                + hint
                + "\"guest\":{\"name\":\"홍길동\",\"email\":\"" + email + "\",\"password\":\"1234\"}}";
    }

    private static String memberBody() {
        return "{\"title\":\"결제가 두 번 됐어요\",\"content\":\"카드 명세서에 두 건이 찍혔습니다.\"}";
    }

    private Ticket onlyTicket() {
        List<Ticket> tickets = ticketRepository.findAll();
        assertThat(tickets).hasSize(1);
        return tickets.get(0);
    }

    @Nested
    @DisplayName("회원 접수")
    class MemberTicket {

        @Test
        @DisplayName("201 과 티켓번호·RECEIVED 를 돌려주고 customer_id 가 채워진다")
        void createsMemberTicket() throws Exception {
            mockMvc.perform(asCustomer(createRequest(memberBody())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                    .andExpect(jsonPath("$.data.ticketNo")
                            .value(Matchers.matchesPattern(TICKET_NO_PATTERN)));

            Ticket ticket = onlyTicket();
            assertThat(ticket.getCustomerId()).isEqualTo(customerId);
            assertThat(ticket.isMemberTicket()).isTrue();
            assertThat(ticket.getGuestEmail()).isNull();
            assertThat(ticket.getGuestPasswordHash()).isNull();
            assertThat(ticket.getChannel()).isEqualTo(TicketChannel.WEB);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.NORMAL);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
        }

        @Test
        @DisplayName("로그인 상태에서 guest 를 함께 보내도 비회원 정보로 저장하지 않는다")
        void ignoresGuestWhenLoggedIn() throws Exception {
            mockMvc.perform(asCustomer(createRequest(guestBody("someone-else@example.com", null))))
                    .andExpect(status().isCreated());

            Ticket ticket = onlyTicket();
            assertThat(ticket.getCustomerId()).isEqualTo(customerId);
            assertThat(ticket.getGuestEmail()).isNull();
            assertThat(ticket.getGuestName()).isNull();
        }
    }

    @Nested
    @DisplayName("비회원 접수")
    class GuestTicket {

        @Test
        @DisplayName("201 로 접수되고 조회 비밀번호는 BCrypt 해시로만 저장된다")
        void hashesGuestPassword() throws Exception {
            mockMvc.perform(createRequest(guestBody("guest-hash@example.com", null)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.ticketId").isNumber());

            Ticket ticket = onlyTicket();
            assertThat(ticket.getCustomerId()).isNull();
            assertThat(ticket.isMemberTicket()).isFalse();
            assertThat(ticket.getGuestEmail()).isEqualTo("guest-hash@example.com");
            assertThat(ticket.getGuestPasswordHash()).isNotEqualTo("1234").startsWith("$2");
            assertThat(passwordEncoder.matches("1234", ticket.getGuestPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("비회원 정보 없이 비로그인 접수하면 TICKET_GUEST_INFO_REQUIRED")
        void rejectsAnonymousWithoutGuestInfo() throws Exception {
            mockMvc.perform(createRequest("{\"title\":\"제목\",\"content\":\"본문\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("TICKET_GUEST_INFO_REQUIRED"));

            assertThat(ticketRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("같은 이메일로 1시간에 6번째 접수는 429 (docs/04 §7)")
        void limitsByGuestEmail() throws Exception {
            String body = guestBody("flooder@example.com", null);
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(createRequest(body)).andExpect(status().isCreated());
            }

            mockMvc.perform(createRequest(body))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"));

            assertThat(ticketRepository.findAll()).hasSize(5);
        }
    }

    @Nested
    @DisplayName("접수 시 확정되는 값")
    class Defaults {

        @Test
        @DisplayName("첫 응답 기한은 접수 시각 + 1440분 (PRD 6.1 NORMAL)")
        void calculatesNormalSlaDueAt() throws Exception {
            mockMvc.perform(createRequest(guestBody("sla@example.com", null)))
                    .andExpect(status().isCreated());

            Ticket ticket = onlyTicket();
            long minutes = Duration.between(ticket.getCreatedAt(), ticket.getFirstResponseDueAt())
                    .toMinutes();
            assertThat(minutes).isBetween(1439L, 1440L);
            assertThat(ticket.getFirstRespondedAt()).isNull();
            assertThat(ticket.isSlaWarned()).isFalse();
            assertThat(ticket.isSlaBreached()).isFalse();
        }

        @Test
        @DisplayName("고객이 고른 유형을 category 에 담는다 — getTicketSummary 의 categoryHint 가 이 값이다")
        void keepsCustomerCategoryHint() throws Exception {
            mockMvc.perform(createRequest(guestBody("hint@example.com", "DELIVERY")))
                    .andExpect(status().isCreated());

            assertThat(onlyTicket().getCategory()).isEqualTo(TicketCategory.DELIVERY);
        }

        @Test
        @DisplayName("유형을 고르지 않으면 ETC")
        void defaultsToEtc() throws Exception {
            mockMvc.perform(createRequest(guestBody("no-hint@example.com", null)))
                    .andExpect(status().isCreated());

            assertThat(onlyTicket().getCategory()).isEqualTo(TicketCategory.ETC);
        }

        @Test
        @DisplayName("CREATE 이력 1건이 남고 비회원은 actorType 이 GUEST")
        void writesCreateHistory() throws Exception {
            mockMvc.perform(createRequest(guestBody("history@example.com", null)))
                    .andExpect(status().isCreated());

            Long ticketId = onlyTicket().getId();
            assertThat(ticketHistoryRepository.findAll()).singleElement().satisfies(history -> {
                assertThat(history.getTicketId()).isEqualTo(ticketId);
                assertThat(history.getAction()).isEqualTo(HistoryAction.CREATE);
                assertThat(history.getFromValue()).isNull();
                assertThat(history.getToValue()).isEqualTo(TicketStatus.RECEIVED.name());
                assertThat(history.getActorType()).isEqualTo(ActorType.GUEST);
                assertThat(history.getActorId()).isNull();
            });
        }

        @Test
        @DisplayName("회원 접수 이력은 actorType 이 MEMBER 이고 actorId 가 채워진다")
        void writesMemberCreateHistory() throws Exception {
            mockMvc.perform(asCustomer(createRequest(memberBody())))
                    .andExpect(status().isCreated());

            assertThat(ticketHistoryRepository.findAll()).singleElement().satisfies(history -> {
                assertThat(history.getActorType()).isEqualTo(ActorType.MEMBER);
                assertThat(history.getActorId()).isEqualTo(customerId);
            });
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        @Test
        @DisplayName("제목 201자는 400")
        void rejectsLongTitle() throws Exception {
            String body = "{\"title\":\"" + "가".repeat(201) + "\",\"content\":\"본문\","
                    + "\"guest\":{\"name\":\"홍길동\",\"email\":\"long-title@example.com\",\"password\":\"1234\"}}";

            mockMvc.perform(createRequest(body)).andExpect(status().isBadRequest());

            assertThat(ticketRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("본문 5001자는 400 — docs/04 §7 각주의 5,000자 제한")
        void rejectsLongContent() throws Exception {
            String body = "{\"title\":\"제목\",\"content\":\"" + "가".repeat(5001) + "\","
                    + "\"guest\":{\"name\":\"홍길동\",\"email\":\"long-content@example.com\",\"password\":\"1234\"}}";

            mockMvc.perform(createRequest(body)).andExpect(status().isBadRequest());

            assertThat(ticketRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("비회원 이메일 형식이 틀리면 400")
        void rejectsInvalidGuestEmail() throws Exception {
            String body = "{\"title\":\"제목\",\"content\":\"본문\","
                    + "\"guest\":{\"name\":\"홍길동\",\"email\":\"not-an-email\",\"password\":\"1234\"}}";

            mockMvc.perform(createRequest(body)).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("첨부 6개는 400 — 5개 제한 (docs/04 §3)")
        void rejectsTooManyAttachments() throws Exception {
            String body = "{\"title\":\"제목\",\"content\":\"본문\",\"attachmentIds\":[1,2,3,4,5,6],"
                    + "\"guest\":{\"name\":\"홍길동\",\"email\":\"many-files@example.com\",\"password\":\"1234\"}}";

            mockMvc.perform(createRequest(body)).andExpect(status().isBadRequest());
        }
    }
}
