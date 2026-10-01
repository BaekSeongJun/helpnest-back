// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.dto.SlaFilter;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.dto.TicketSearchCondition;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.ConsoleTicketService;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.security.JwtProvider;

import jakarta.persistence.EntityManager;

/**
 * 콘솔 티켓 목록 검색·상세 통합 테스트 (docs/04 §7, 화면 CS-01·CS-02).
 *
 * <h2>SLA 테스트를 위해 created_at 을 뒤로 돌린다</h2>
 * {@code created_at} 은 {@code @CreationTimestamp} 라 빌더로 지정할 수 없다. 임박 판정은
 * "접수 시각 + 비율×기한"이 기준이므로 네이티브 UPDATE 로 접수 시각을 과거로 옮긴 뒤
 * {@code EntityManager} 를 비워 DB 상태로 다시 읽게 한다.
 *
 * <h2>N+1 을 통계로 측정한다</h2>
 * "쿼리 수가 티켓 건수에 비례하지 않는다"는 눈으로 로그를 보는 대신 Hibernate
 * {@link Statistics} 의 PreparedStatement 수로 확인한다. 그래서 이 테스트만
 * {@code generate_statistics=true} 로 컨텍스트를 따로 띄운다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Transactional
@DisplayName("GET /api/console/tickets — 목록 검색·상세")
class ConsoleTicketSearchApiTest {

    /** PRD 6.1 NORMAL 1440분 × 기본 warning_ratio 0.80 = 1152분 */
    private static final int NORMAL_WARNING_MINUTES = 1152;
    private static final String INTERNAL_MEMO = "내부공유: 지난달 환불 민원 있음";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketReplyRepository ticketReplyRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    ConsoleTicketService consoleTicketService;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    EntityManager em;

    private Long customerId;
    private Long assigneeId;
    private Long otherAgentId;
    private Long leadId;
    private String assigneeToken;
    private String leadToken;

    @BeforeEach
    void loginAsSeedMembers() {
        customerId = memberId("customer1@helpnest.local");
        assigneeId = memberId("agent1@helpnest.local");
        otherAgentId = memberId("agent2@helpnest.local");
        leadId = memberId("lead@helpnest.local");

        assigneeToken = jwtProvider.createAccessToken(assigneeId, "AGENT");
        leadToken = jwtProvider.createAccessToken(leadId, "LEAD");
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    private Ticket givenTicket(TicketStatus status, TicketPriority priority, TicketCategory category,
            Long agentId, OffsetDateTime dueAt) {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .customerId(customerId)
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다.")
                .channel(TicketChannel.WEB)
                .category(category)
                .firstResponseDueAt(dueAt)
                .build());
        if (agentId != null) {
            ticket.assignTo(agentId, OffsetDateTime.now());
        }
        if (ticket.getStatus() != status) {
            ticket.changeStatusTo(status, OffsetDateTime.now());
        }
        if (priority != TicketPriority.NORMAL) {
            ticket.applyClassification(ticket.getCategory(), priority, ticket.getSentiment());
        }
        return ticket;
    }

    /** 기본값: ASSIGNED / NORMAL / DELIVERY / 담당자 있음 / 기한 하루 뒤 */
    private Ticket givenTicket() {
        return givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL, TicketCategory.DELIVERY,
                assigneeId, OffsetDateTime.now().plusDays(1));
    }

    /** 접수 시각을 과거로 돌린다. @CreationTimestamp 는 빌더로 지정할 수 없다 */
    private void backdate(Ticket ticket, int minutes) {
        em.flush();
        em.createNativeQuery("update ticket set created_at = :at where ticket_id = :id")
                .setParameter("at", OffsetDateTime.now().minusMinutes(minutes))
                .setParameter("id", ticket.getId())
                .executeUpdate();
        em.clear();
    }

    private static TicketSearchCondition noFilter() {
        return new TicketSearchCondition(null, null, null, null, null, null, null);
    }

    private PageResponse<TicketListItemResponse> searchAsLead(TicketSearchCondition condition) {
        return consoleTicketService.search(condition, ActorRole.LEAD, leadId, page());
    }

    private static Pageable page() {
        return PageRequest.of(0, 20, Sort.by("firstResponseDueAt").ascending());
    }

    private static List<Long> idsOf(PageResponse<TicketListItemResponse> page) {
        return page.content().stream().map(TicketListItemResponse::ticketId).toList();
    }

    @Nested
    @DisplayName("필터 단독")
    class SingleFilter {

        @Test
        @DisplayName("status")
        void byStatus() {
            Ticket assigned = givenTicket();
            Ticket resolved = givenTicket(TicketStatus.RESOLVED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusDays(1));

            var result = searchAsLead(new TicketSearchCondition(TicketStatus.RESOLVED, null, null,
                    null, null, null, null));

            assertThat(idsOf(result)).contains(resolved.getId()).doesNotContain(assigned.getId());
        }

        @Test
        @DisplayName("priority")
        void byPriority() {
            Ticket normal = givenTicket();
            Ticket urgent = givenTicket(TicketStatus.ASSIGNED, TicketPriority.URGENT,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusHours(1));

            var result = searchAsLead(new TicketSearchCondition(null, TicketPriority.URGENT, null,
                    null, null, null, null));

            assertThat(idsOf(result)).contains(urgent.getId()).doesNotContain(normal.getId());
        }

        @Test
        @DisplayName("category")
        void byCategory() {
            Ticket delivery = givenTicket();
            Ticket refund = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.REFUND, assigneeId, OffsetDateTime.now().plusDays(1));

            var result = searchAsLead(new TicketSearchCondition(null, null, TicketCategory.REFUND,
                    null, null, null, null));

            assertThat(idsOf(result)).contains(refund.getId()).doesNotContain(delivery.getId());
        }

        @Test
        @DisplayName("agentId")
        void byAgentId() {
            Ticket mine = givenTicket();
            Ticket others = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, otherAgentId, OffsetDateTime.now().plusDays(1));

            var result = searchAsLead(new TicketSearchCondition(null, null, null, otherAgentId,
                    null, null, null));

            assertThat(idsOf(result)).contains(others.getId()).doesNotContain(mine.getId());
        }

        @Test
        @DisplayName("unassigned — 미배정 탭(agent_id IS NULL)")
        void byUnassigned() {
            Ticket assigned = givenTicket();
            Ticket unassigned = givenTicket(TicketStatus.RECEIVED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, null, OffsetDateTime.now().plusDays(1));

            var result = searchAsLead(new TicketSearchCondition(null, null, null, null, true, null,
                    null));

            assertThat(idsOf(result)).contains(unassigned.getId())
                    .doesNotContain(assigned.getId());
        }

        @Test
        @DisplayName("keyword — 티켓번호·제목·본문 부분 일치(대소문자 무시)")
        void byKeyword() {
            Ticket target = givenTicket();

            assertThat(idsOf(searchAsLead(keyword("배송 조회"))))
                    .contains(target.getId());
            assertThat(idsOf(searchAsLead(keyword(target.getTicketNo().toLowerCase()))))
                    .contains(target.getId());
            assertThat(idsOf(searchAsLead(keyword("존재하지않는단어"))))
                    .doesNotContain(target.getId());
        }

        private TicketSearchCondition keyword(String keyword) {
            return new TicketSearchCondition(null, null, null, null, null, null, keyword);
        }
    }

    @Nested
    @DisplayName("SLA 필터 경계값")
    class SlaFilterBoundary {

        private TicketSearchCondition sla(SlaFilter sla) {
            return new TicketSearchCondition(null, null, null, null, null, sla, null);
        }

        @Test
        @DisplayName("WARNING — 임박 시각을 넘겼고 기한은 남은 티켓만")
        void warning() {
            Ticket warned = givenTicket();
            backdate(warned, NORMAL_WARNING_MINUTES + 10);
            Ticket notYet = givenTicket();
            backdate(notYet, NORMAL_WARNING_MINUTES - 10);

            var result = searchAsLead(sla(SlaFilter.WARNING));

            assertThat(idsOf(result)).contains(warned.getId()).doesNotContain(notYet.getId());
        }

        @Test
        @DisplayName("WARNING — 이미 응답한 티켓은 임박이 아니다")
        void warningExcludesResponded() {
            Ticket responded = givenTicket();
            responded.markFirstResponded(OffsetDateTime.now());
            backdate(responded, NORMAL_WARNING_MINUTES + 10);

            assertThat(idsOf(searchAsLead(sla(SlaFilter.WARNING))))
                    .doesNotContain(responded.getId());
        }

        @Test
        @DisplayName("WARNING — 기한을 이미 넘긴 티켓은 임박이 아니라 위반이다")
        void warningExcludesBreached() {
            Ticket breached = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().minusMinutes(10));
            backdate(breached, NORMAL_WARNING_MINUTES + 10);

            assertThat(idsOf(searchAsLead(sla(SlaFilter.WARNING))))
                    .doesNotContain(breached.getId());
            assertThat(idsOf(searchAsLead(sla(SlaFilter.BREACHED))))
                    .contains(breached.getId());
        }

        @Test
        @DisplayName("BREACHED — 기한을 넘기고 미응답인 티켓")
        void breached() {
            Ticket breached = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().minusMinutes(1));
            Ticket inTime = givenTicket();

            var result = searchAsLead(sla(SlaFilter.BREACHED));

            assertThat(idsOf(result)).contains(breached.getId()).doesNotContain(inTime.getId());
        }

        @Test
        @DisplayName("BREACHED — 늦게라도 응답했어도 위반 기록이 있으면 위반이다")
        void breachedKeepsFlaggedTicket() {
            Ticket lateResponded = givenTicket();
            lateResponded.markSlaBreached();
            lateResponded.markFirstResponded(OffsetDateTime.now());

            assertThat(idsOf(searchAsLead(sla(SlaFilter.BREACHED))))
                    .contains(lateResponded.getId());
        }
    }

    @Nested
    @DisplayName("필터 조합")
    class CombinedFilter {

        @Test
        @DisplayName("status + priority")
        void statusAndPriority() {
            Ticket match = givenTicket(TicketStatus.IN_PROGRESS, TicketPriority.URGENT,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusHours(1));
            Ticket statusOnly = givenTicket(TicketStatus.IN_PROGRESS, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusDays(1));
            Ticket priorityOnly = givenTicket(TicketStatus.ASSIGNED, TicketPriority.URGENT,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusHours(1));

            var result = searchAsLead(new TicketSearchCondition(TicketStatus.IN_PROGRESS,
                    TicketPriority.URGENT, null, null, null, null, null));

            assertThat(idsOf(result)).contains(match.getId())
                    .doesNotContain(statusOnly.getId(), priorityOnly.getId());
        }

        @Test
        @DisplayName("category + agentId + keyword")
        void categoryAgentKeyword() {
            Ticket match = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.REFUND, otherAgentId, OffsetDateTime.now().plusDays(1));
            Ticket wrongAgent = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.REFUND, assigneeId, OffsetDateTime.now().plusDays(1));

            var result = searchAsLead(new TicketSearchCondition(null, null, TicketCategory.REFUND,
                    otherAgentId, null, null, "배송"));

            assertThat(idsOf(result)).contains(match.getId()).doesNotContain(wrongAgent.getId());
        }
    }

    @Nested
    @DisplayName("권한 — AGENT 는 본인 담당분만")
    class Permission {

        @Test
        @DisplayName("agentId 파라미터로 남의 티켓을 조회해도 본인 것만 나온다")
        void agentCannotQueryOthersViaParam() throws Exception {
            Ticket mine = givenTicket();
            Ticket others = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, otherAgentId, OffsetDateTime.now().plusDays(1));

            String body = mockMvc.perform(get("/api/console/tickets")
                    .param("agentId", String.valueOf(otherAgentId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + assigneeToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains(mine.getTicketNo()).doesNotContain(others.getTicketNo());
        }

        @Test
        @DisplayName("필터 없이 조회해도 남의 티켓은 보이지 않는다")
        void agentSeesOnlyOwnByDefault() {
            Ticket mine = givenTicket();
            Ticket others = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, otherAgentId, OffsetDateTime.now().plusDays(1));

            var result = consoleTicketService.search(noFilter(), ActorRole.AGENT, assigneeId, page());

            assertThat(idsOf(result)).contains(mine.getId()).doesNotContain(others.getId());
        }

        @Test
        @DisplayName("미배정 탭은 상담원도 볼 수 있다 — 담당자 조건을 함께 걸면 항상 비어 버린다")
        void agentCanSeeUnassignedQueue() {
            Ticket unassigned = givenTicket(TicketStatus.RECEIVED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, null, OffsetDateTime.now().plusDays(1));
            Ticket mine = givenTicket();

            var result = consoleTicketService.search(
                    new TicketSearchCondition(null, null, null, null, true, null, null),
                    ActorRole.AGENT, assigneeId, page());

            assertThat(idsOf(result)).contains(unassigned.getId()).doesNotContain(mine.getId());
        }

        @Test
        @DisplayName("LEAD 는 전체를 본다")
        void leadSeesAll() {
            Ticket mine = givenTicket();
            Ticket others = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, otherAgentId, OffsetDateTime.now().plusDays(1));

            assertThat(idsOf(searchAsLead(noFilter())))
                    .contains(mine.getId(), others.getId());
        }
    }

    @Nested
    @DisplayName("정렬과 페이지")
    class SortingAndPaging {

        @Test
        @DisplayName("기본 정렬은 SLA 임박순 — 기한이 가까운 것이 먼저")
        void sortedByDueAtAsc() {
            Ticket later = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusDays(2));
            Ticket sooner = givenTicket(TicketStatus.ASSIGNED, TicketPriority.URGENT,
                    TicketCategory.DELIVERY, assigneeId, OffsetDateTime.now().plusMinutes(30));

            List<Long> ids = idsOf(searchAsLead(noFilter()));

            assertThat(ids.indexOf(sooner.getId())).isLessThan(ids.indexOf(later.getId()));
        }

        @Test
        @DisplayName("페이지 메타(totalElements·totalPages)가 채워진다")
        void pageMeta() {
            givenTicket();
            givenTicket();
            givenTicket();

            var result = consoleTicketService.search(noFilter(), ActorRole.LEAD, leadId,
                    PageRequest.of(0, 2, Sort.by("firstResponseDueAt").ascending()));

            assertThat(result.content()).hasSize(2);
            assertThat(result.page()).isZero();
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("GET /{id} — 콘솔 상세")
    class Detail {

        @Test
        @DisplayName("내부 메모가 포함된다 — 고객용과 다른 점")
        void includesInternalMemo() throws Exception {
            Ticket ticket = givenTicket();
            ticketReplyRepository.save(TicketReply.builder()
                    .ticketId(ticket.getId()).writerId(assigneeId).writerType(WriterType.AGENT)
                    .content(INTERNAL_MEMO).isInternal(true).build());
            ticketReplyRepository.save(TicketReply.builder()
                    .ticketId(ticket.getId()).writerId(assigneeId).writerType(WriterType.AGENT)
                    .content("고객님께 안내드립니다.").isInternal(false).build());

            String body = mockMvc.perform(get("/api/console/tickets/{id}", ticket.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + assigneeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.replies.length()").value(2))
                    .andExpect(jsonPath("$.data.content").value("배송 조회가 안 됩니다."))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).contains(INTERNAL_MEMO);
        }

        @Test
        @DisplayName("담당자가 아니어도 열 수 있다 — 인수인계·팀장 확인이 정상 업무다")
        void nonAssigneeCanOpen() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, TicketPriority.NORMAL,
                    TicketCategory.DELIVERY, otherAgentId, OffsetDateTime.now().plusDays(1));

            mockMvc.perform(get("/api/console/tickets/{id}", ticket.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + assigneeToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("없는 티켓은 404")
        void unknownTicket() throws Exception {
            mockMvc.perform(get("/api/console/tickets/{id}", 9_999_999L)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + leadToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("성능 — N+1 부재")
    class QueryCount {

        @Test
        @DisplayName("목록 조회 쿼리 수가 티켓 건수에 비례하지 않는다")
        void queryCountDoesNotScaleWithRows() {
            Statistics statistics = em.getEntityManagerFactory()
                    .unwrap(SessionFactory.class).getStatistics();

            givenTicket();
            long withOneRow = countQueries(statistics);

            for (int i = 0; i < 5; i++) {
                givenTicket();
            }
            long withSixRows = countQueries(statistics);

            // 목록 1 + 카운트 1 + 이름 일괄 조회 1 = 건수와 무관하게 일정하다
            assertThat(withSixRows).isEqualTo(withOneRow);
            assertThat(withOneRow).isLessThanOrEqualTo(4);
        }

        private long countQueries(Statistics statistics) {
            em.flush();
            statistics.clear();
            consoleTicketService.search(noFilter(), ActorRole.LEAD, leadId, page());
            return statistics.getPrepareStatementCount();
        }
    }
}
