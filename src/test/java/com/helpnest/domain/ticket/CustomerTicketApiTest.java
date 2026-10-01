// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.port.ResolvedReply;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;

/**
 * 고객용 조회·답글 통합 테스트 (docs/04 §7, 화면 CU-07·CU-08, FR-INQ-06).
 *
 * <h2>이 테스트의 최우선 검증</h2>
 * <b>내부 메모가 고객 응답에 섞이지 않는지</b>다. 필드 하나를 확인하는 것으로는 부족해서
 * 응답 본문 문자열 전체에 메모 내용이 없는지 본다 — 어느 필드로 새든 걸린다.
 *
 * <h2>비회원 경로를 함께 검증하는 이유</h2>
 * 이 태스크 계획 당시에는 Guest 토큰이 없어 비회원 분기를 보류하도록 되어 있었으나, CR #34 로
 * 규약이 확정되고 백성준의 {@code POST /api/auth/guest} 가 머지되어(#40) 이제 검증할 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("고객용 티켓 조회·답글 API")
class CustomerTicketApiTest {

    private static final String INTERNAL_MEMO = "내부공유: 이 고객 지난달 환불 민원 있었음";
    private static final String PUBLIC_REPLY = "확인했습니다. 오늘 재발송해 드릴게요.";
    private static final String GUEST_NAME = "김비회원";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketReplyRepository ticketReplyRepository;
    @Autowired
    TicketHistoryRepository ticketHistoryRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    TicketQueryPort ticketQueryPort;
    @Autowired
    JwtProvider jwtProvider;

    private Long customerId;
    private Long otherCustomerId;
    private Long agentId;
    private String customerToken;
    private String otherCustomerToken;

    @BeforeEach
    void loginAsSeedMembers() {
        customerId = memberId("customer1@helpnest.local");
        otherCustomerId = memberId("customer2@helpnest.local");
        agentId = memberId("agent1@helpnest.local");

        customerToken = jwtProvider.createAccessToken(customerId, "CUSTOMER");
        otherCustomerToken = jwtProvider.createAccessToken(otherCustomerId, "CUSTOMER");
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    private Ticket givenTicket(Long ownerId, TicketStatus status) {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .customerId(ownerId)
                .guestName(ownerId == null ? GUEST_NAME : null)
                .guestEmail(ownerId == null ? "guest-view@example.com" : null)
                .guestPasswordHash(ownerId == null ? "$2a$10$dummy" : null)
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다.")
                .channel(TicketChannel.WEB)
                .category(TicketCategory.DELIVERY)
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build());
        ticket.assignTo(agentId, OffsetDateTime.now());
        if (ticket.getStatus() != status) {
            ticket.changeStatusTo(status, OffsetDateTime.now());
        }
        return ticket;
    }

    /** 공개 답변 1건 + 내부 메모 1건이 섞인 티켓 */
    private Ticket givenTicketWithReplies(Long ownerId, TicketStatus status) {
        Ticket ticket = givenTicket(ownerId, status);
        saveReply(ticket, WriterType.AGENT, agentId, PUBLIC_REPLY, false);
        saveReply(ticket, WriterType.AGENT, agentId, INTERNAL_MEMO, true);
        return ticket;
    }

    private TicketReply saveReply(Ticket ticket, WriterType type, Long writerId, String content,
            boolean internal) {
        return ticketReplyRepository.save(TicketReply.builder()
                .ticketId(ticket.getId())
                .writerId(writerId)
                .writerType(type)
                .content(content)
                .isInternal(internal)
                .build());
    }

    private ResultActions getDetail(Long ticketId, String token) throws Exception {
        return mockMvc.perform(get("/api/tickets/{id}", ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postReply(Long ticketId, String token, String content) throws Exception {
        return mockMvc.perform(post("/api/tickets/{id}/replies", ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"%s\"}".formatted(content)));
    }

    @Nested
    @DisplayName("GET /api/tickets/my — 내 문의 목록")
    class MyTickets {

        @Test
        @DisplayName("내 티켓만 최신순으로 보이고 남의 티켓은 포함되지 않는다")
        void showsOnlyMine() throws Exception {
            givenTicket(customerId, TicketStatus.RECEIVED);
            givenTicket(customerId, TicketStatus.RESOLVED);
            Ticket others = givenTicket(otherCustomerId, TicketStatus.RECEIVED);

            String body = mockMvc.perform(get("/api/tickets/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(2)))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).doesNotContain(others.getTicketNo());
        }

        @Test
        @DisplayName("고객 이름과 담당자 이름이 채워진다 — 행마다 회원 API 를 또 부르지 않게")
        void fillsNames() throws Exception {
            givenTicket(customerId, TicketStatus.ASSIGNED);

            mockMvc.perform(get("/api/tickets/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].agentName").value("이상담"))
                    .andExpect(jsonPath("$.data.content[0].customerName").isNotEmpty());
        }

        @Test
        @DisplayName("Guest 토큰으로는 목록을 볼 수 없다 — 티켓 1건에만 유효한 토큰이다")
        void guestTokenCannotListTickets() throws Exception {
            Ticket guestTicket = givenTicket(null, TicketStatus.RECEIVED);
            String guestToken = jwtProvider.createGuestToken(guestTicket.getId());

            mockMvc.perform(get("/api/tickets/my")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("page/size 파라미터가 적용된다")
        void appliesPaging() throws Exception {
            givenTicket(customerId, TicketStatus.RECEIVED);
            givenTicket(customerId, TicketStatus.RECEIVED);

            mockMvc.perform(get("/api/tickets/my").param("size", "1")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content", hasSize(1)))
                    .andExpect(jsonPath("$.data.totalElements").value(2))
                    .andExpect(jsonPath("$.data.totalPages").value(2));
        }
    }

    @Nested
    @DisplayName("GET /api/tickets/{id} — 문의 상세")
    class Detail {

        @Test
        @DisplayName("내부 메모가 응답 본문 어디에도 나타나지 않는다")
        void neverLeaksInternalMemo() throws Exception {
            Ticket ticket = givenTicketWithReplies(customerId, TicketStatus.IN_PROGRESS);

            String body = getDetail(ticket.getId(), customerToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.replies", hasSize(1)))
                    .andExpect(jsonPath("$.data.replies[0].content").value(PUBLIC_REPLY))
                    .andExpect(jsonPath("$.data.replies[0].isInternal").value(false))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).doesNotContain(INTERNAL_MEMO);
            // 메모가 DB 에는 그대로 있어야 한다 — 걸러진 것이지 사라진 것이 아니다
            assertThat(ticketReplyRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()))
                    .hasSize(2);
        }

        @Test
        @DisplayName("본문과 상태·SLA 필드가 함께 내려온다")
        void returnsDetailFields() throws Exception {
            Ticket ticket = givenTicket(customerId, TicketStatus.ASSIGNED);

            getDetail(ticket.getId(), customerToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ticketNo").value(ticket.getTicketNo()))
                    .andExpect(jsonPath("$.data.content").value("배송 조회가 안 됩니다."))
                    .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                    .andExpect(jsonPath("$.data.agentName").value("이상담"))
                    .andExpect(jsonPath("$.data.firstResponseDueAt").isNotEmpty());
        }

        @Test
        @DisplayName("남의 티켓은 404 — 403 을 주면 티켓 존재 여부가 드러난다")
        void othersTicketIsNotFound() throws Exception {
            Ticket ticket = givenTicket(otherCustomerId, TicketStatus.RECEIVED);

            getDetail(ticket.getId(), customerToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }

        @Test
        @DisplayName("없는 티켓도 같은 404 — 남의 티켓과 응답이 구분되지 않아야 한다")
        void unknownTicketIsSameResponse() throws Exception {
            getDetail(9_999_999L, customerToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }

        @Test
        @DisplayName("Guest 토큰으로 자기 티켓을 볼 수 있다")
        void guestCanViewOwnTicket() throws Exception {
            Ticket ticket = givenTicketWithReplies(null, TicketStatus.IN_PROGRESS);
            String guestToken = jwtProvider.createGuestToken(ticket.getId());

            String body = getDetail(ticket.getId(), guestToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.customerName").value(GUEST_NAME))
                    .andExpect(jsonPath("$.data.replies", hasSize(1)))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).doesNotContain(INTERNAL_MEMO);
        }

        @Test
        @DisplayName("Guest 토큰으로 다른 티켓을 요청하면 404 — 토큰은 발급 대상 1건에만 유효하다")
        void guestTokenIsScopedToOneTicket() throws Exception {
            Ticket mine = givenTicket(null, TicketStatus.RECEIVED);
            Ticket others = givenTicket(customerId, TicketStatus.RECEIVED);
            String guestToken = jwtProvider.createGuestToken(mine.getId());

            getDetail(others.getId(), guestToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("POST /api/tickets/{id}/replies — 고객 추가 답글")
    class CustomerReply {

        @Test
        @DisplayName("RESOLVED 에서 답글을 달면 IN_PROGRESS 로 재문의 전이된다")
        void reopensResolvedTicket() throws Exception {
            Ticket ticket = givenTicket(customerId, TicketStatus.RESOLVED);

            postReply(ticket.getId(), customerToken, "아직 안 왔는데요?")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.writerType").value("CUSTOMER"))
                    .andExpect(jsonPath("$.data.isInternal").value(false));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(ticketHistoryRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getFromValue()).isEqualTo("RESOLVED");
                        assertThat(h.getToValue()).isEqualTo("IN_PROGRESS");
                        assertThat(h.getMemo()).isEqualTo("고객 재문의");
                        assertThat(h.getActorId()).isEqualTo(customerId);
                    });
        }

        @Test
        @DisplayName("IN_PROGRESS 에서는 상태가 그대로다 — 전이할 것이 없다")
        void keepsStatusWhenInProgress() throws Exception {
            Ticket ticket = givenTicket(customerId, TicketStatus.IN_PROGRESS);

            postReply(ticket.getId(), customerToken, "추가 정보 드립니다.")
                    .andExpect(status().isCreated());

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(ticketHistoryRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()))
                    .isEmpty();
        }

        @Test
        @DisplayName("CLOSED 티켓에는 답글을 달 수 없다 — 409 TICKET_ALREADY_CLOSED")
        void rejectsClosedTicket() throws Exception {
            Ticket ticket = givenTicket(customerId, TicketStatus.CLOSED);

            postReply(ticket.getId(), customerToken, "하나 더 물어볼게요")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_CLOSED"));

            assertThat(ticketReplyRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()))
                    .isEmpty();
        }

        @Test
        @DisplayName("남의 티켓에는 404")
        void rejectsOthersTicket() throws Exception {
            Ticket ticket = givenTicket(otherCustomerId, TicketStatus.RECEIVED);

            postReply(ticket.getId(), customerToken, "끼어들기")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }

        @Test
        @DisplayName("비회원 답글은 writerType=GUEST 이고 작성자 이름에 비회원 이름이 들어간다")
        void guestReply() throws Exception {
            Ticket ticket = givenTicket(null, TicketStatus.RESOLVED);
            String guestToken = jwtProvider.createGuestToken(ticket.getId());

            postReply(ticket.getId(), guestToken, "아직 못 받았습니다.")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.writerType").value("GUEST"))
                    .andExpect(jsonPath("$.data.writerName").value(GUEST_NAME));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(ticketReplyRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId()))
                    .singleElement()
                    .satisfies(r -> assertThat(r.getWriterId()).isNull());
        }

        @Test
        @DisplayName("빈 본문은 400")
        void rejectsBlankContent() throws Exception {
            Ticket ticket = givenTicket(customerId, TicketStatus.RECEIVED);

            postReply(ticket.getId(), customerToken, "   ").andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("TicketQueryPort 실구현 (신수진 AI 경로)")
    class QueryPort {

        @Test
        @DisplayName("getTicketSummary 는 제목·본문·유형을 넘기고 본문을 마스킹하지 않는다")
        void returnsSummary() {
            Ticket ticket = givenTicket(customerId, TicketStatus.RECEIVED);

            assertThat(ticketQueryPort.getTicketSummary(ticket.getId()))
                    .satisfies(s -> {
                        assertThat(s.title()).isEqualTo("주문한 상품이 아직 안 왔어요");
                        assertThat(s.content()).isEqualTo("배송 조회가 안 됩니다.");
                        assertThat(s.categoryHint()).isEqualTo("DELIVERY");
                    });
        }

        @Test
        @DisplayName("없는 티켓은 TICKET_NOT_FOUND — null 을 주면 분류가 조용히 비어 버린다")
        void throwsOnUnknownTicket() {
            assertThatThrownBy(() -> ticketQueryPort.getTicketSummary(-1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                            .isEqualTo("TICKET_NOT_FOUND"));
        }

        @Test
        @DisplayName("findResolvedReplies 는 내부 메모와 고객 답변을 제외하고 상담원 공개 답변만 준다")
        void excludesInternalAndCustomerReplies() {
            Ticket resolved = givenTicket(customerId, TicketStatus.RESOLVED);
            saveReply(resolved, WriterType.AGENT, agentId, PUBLIC_REPLY, false);
            saveReply(resolved, WriterType.AGENT, agentId, INTERNAL_MEMO, true);
            saveReply(resolved, WriterType.CUSTOMER, customerId, "고객이 쓴 글", false);

            List<ResolvedReply> replies = ticketQueryPort.findResolvedReplies("DELIVERY", 10);

            assertThat(replies).extracting(ResolvedReply::content)
                    .contains(PUBLIC_REPLY)
                    .doesNotContain(INTERNAL_MEMO, "고객이 쓴 글");
        }

        @Test
        @DisplayName("해결되지 않은 티켓의 답변은 참고 자료가 아니다")
        void excludesUnresolvedTickets() {
            Ticket inProgress = givenTicket(customerId, TicketStatus.IN_PROGRESS);
            saveReply(inProgress, WriterType.AGENT, agentId, "아직 처리 중인 답변", false);

            assertThat(ticketQueryPort.findResolvedReplies("DELIVERY", 10))
                    .extracting(ResolvedReply::content)
                    .doesNotContain("아직 처리 중인 답변");
        }

        @Test
        @DisplayName("소문자·공백이 섞인 유형도 받고, 목록 밖 값은 거부한다")
        void parsesCategoryLeniently() {
            assertThat(ticketQueryPort.findResolvedReplies(" delivery ", 1)).isNotNull();
            assertThat(ticketQueryPort.findResolvedReplies(null, 10)).isEmpty();
            assertThatThrownBy(() -> ticketQueryPort.findResolvedReplies("SPACE_TRAVEL", 1))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
