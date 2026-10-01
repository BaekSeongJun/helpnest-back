// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Comparator;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.dto.TicketHistoryResponse;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;

/**
 * 상태 변경·이력 조회 통합 테스트 (docs/04 §7, PRD 5장 전이표, FR-TKT-03).
 *
 * <h2>전이표 7건을 HTTP 로 전부 검증하지 않는 이유</h2>
 * 전이표의 허용 전이 7건 중 3건은 목적지가 ASSIGNED(배정·재배정)이고, 나머지 4건 중 2건은
 * 수행자가 SYSTEM·CUSTOMER 다. 즉 <b>콘솔 API 로 도달할 수 있는 전이는 4건뿐</b>이므로
 * HTTP 계층에서는 그 4건과 거부 응답을, 수행자가 상담원이 아닌 전이는 서비스 호출로 검증한다.
 * 전이표 자체의 7건 전수 검증은 S0 의 {@code TicketStateMachineTest} 가 이미 하고 있어
 * 여기서 반복하면 같은 표를 두 번 적는 것이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
@DisplayName("PATCH /api/console/tickets/{id}/status — 상태 변경")
class TicketStatusApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketHistoryRepository ticketHistoryRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    TicketService ticketService;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    ApplicationEvents events;

    private Long customerId;
    private Long assigneeId;
    private Long otherAgentId;
    private Long leadId;
    private String assigneeToken;
    private String otherAgentToken;
    private String leadToken;
    private String customerToken;

    @BeforeEach
    void loginAsSeedMembers() {
        customerId = memberId("customer1@helpnest.local");
        assigneeId = memberId("agent1@helpnest.local");
        otherAgentId = memberId("agent2@helpnest.local");
        leadId = memberId("lead@helpnest.local");

        assigneeToken = jwtProvider.createAccessToken(assigneeId, "AGENT");
        otherAgentToken = jwtProvider.createAccessToken(otherAgentId, "AGENT");
        leadToken = jwtProvider.createAccessToken(leadId, "LEAD");
        customerToken = jwtProvider.createAccessToken(customerId, "CUSTOMER");
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    /**
     * 주어진 상태의 티켓. assignTo 가 상태를 ASSIGNED 로 바꾸므로 담당자를 먼저 붙이고
     * 목표 상태를 나중에 덮어쓴다.
     */
    private Ticket givenTicket(TicketStatus status, Long agentId) {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .customerId(customerId)
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build());
        if (agentId != null) {
            ticket.assignTo(agentId, OffsetDateTime.now());
        }
        if (ticket.getStatus() != status) {
            ticket.changeStatusTo(status, OffsetDateTime.now());
        }
        return ticket;
    }

    private ResultActions patchStatus(Long ticketId, String token, TicketStatus toStatus, String memo)
            throws Exception {
        String body = "{\"toStatus\":\"%s\"%s}".formatted(toStatus,
                memo == null ? "" : ",\"memo\":\"%s\"".formatted(memo));
        return mockMvc.perform(patch("/api/console/tickets/{id}/status", ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<TicketHistory> historiesOf(Ticket ticket) {
        return ticketHistoryRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
    }

    @Nested
    @DisplayName("허용 전이")
    class Allowed {

        @Test
        @DisplayName("담당 상담원의 ASSIGNED → IN_PROGRESS 는 200 이고 STATUS_CHANGE 이력이 남는다")
        void assignedToInProgress() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);

            patchStatus(ticket.getId(), assigneeToken, TicketStatus.IN_PROGRESS, "확인 시작합니다")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getFromValue()).isEqualTo("ASSIGNED");
                        assertThat(h.getToValue()).isEqualTo("IN_PROGRESS");
                        assertThat(h.getActorId()).isEqualTo(assigneeId);
                        assertThat(h.getActorType()).isEqualTo(ActorType.MEMBER);
                        assertThat(h.getMemo()).isEqualTo("확인 시작합니다");
                    });
        }

        @Test
        @DisplayName("IN_PROGRESS → RESOLVED 면 resolved_at 이 채워지고 이벤트가 발행된다")
        void inProgressToResolved() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.IN_PROGRESS, assigneeId);

            patchStatus(ticket.getId(), assigneeToken, TicketStatus.RESOLVED, null)
                    .andExpect(status().isOk());

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
            assertThat(ticket.getResolvedAt()).isNotNull();
            assertThat(events.stream(TicketStatusChangedEvent.class))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.ticketId()).isEqualTo(ticket.getId());
                        assertThat(e.from()).isEqualTo(TicketStatus.IN_PROGRESS);
                        assertThat(e.to()).isEqualTo(TicketStatus.RESOLVED);
                        assertThat(e.actorId()).isEqualTo(assigneeId);
                    });
        }

        @Test
        @DisplayName("LEAD 는 담당자가 아니어도 남의 티켓 상태를 바꿀 수 있다")
        void leadCanChangeOthersTicket() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.IN_PROGRESS, assigneeId);

            patchStatus(ticket.getId(), leadToken, TicketStatus.RESOLVED, null)
                    .andExpect(status().isOk());

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .singleElement()
                    .satisfies(h -> assertThat(h.getActorId()).isEqualTo(leadId));
        }

        @Test
        @DisplayName("고객 재문의 RESOLVED → IN_PROGRESS 는 CUSTOMER 역할로 허용된다")
        void customerReopen() {
            Ticket ticket = givenTicket(TicketStatus.RESOLVED, assigneeId);

            ticketService.changeStatus(ticket.getId(), TicketStatus.IN_PROGRESS, null, customerId,
                    ActorRole.CUSTOMER);

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("시스템 자동 종료 RESOLVED → CLOSED 는 actorId 없이 SYSTEM 이력으로 남는다")
        void systemClose() {
            Ticket ticket = givenTicket(TicketStatus.RESOLVED, assigneeId);

            ticketService.changeStatus(ticket.getId(), TicketStatus.CLOSED, "72시간 경과", null,
                    ActorRole.SYSTEM);

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
            assertThat(ticket.getClosedAt()).isNotNull();
            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getActorType()).isEqualTo(ActorType.SYSTEM);
                        assertThat(h.getActorId()).isNull();
                    });
        }
    }

    @Nested
    @DisplayName("불허 전이")
    class Rejected {

        @Test
        @DisplayName("RECEIVED → RESOLVED 는 409 TICKET_INVALID_TRANSITION (docs/04 §1.1 예시)")
        void receivedToResolved() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.RECEIVED, null);

            patchStatus(ticket.getId(), leadToken, TicketStatus.RESOLVED, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("TICKET_INVALID_TRANSITION"))
                    .andExpect(jsonPath("$.error.message")
                            .value("RECEIVED에서 RESOLVED로 변경할 수 없습니다."));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
            assertThat(historiesOf(ticket)).isEmpty();
        }

        @Test
        @DisplayName("ASSIGNED → CLOSED 는 409 — 해결을 건너뛰고 종료할 수 없다")
        void assignedToClosed() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);

            patchStatus(ticket.getId(), assigneeToken, TicketStatus.CLOSED, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TICKET_INVALID_TRANSITION"));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(ticket.getClosedAt()).isNull();
        }

        @Test
        @DisplayName("CLOSED 는 어떤 상태로도 바꿀 수 없다 (종료 후 불변)")
        void closedIsFinal() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.CLOSED, assigneeId);

            patchStatus(ticket.getId(), leadToken, TicketStatus.IN_PROGRESS, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TICKET_INVALID_TRANSITION"));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
        }

        @Test
        @DisplayName("RESOLVED → CLOSED 는 상담원 역할로는 불허다 (수행자 SYSTEM·CUSTOMER)")
        void agentCannotClose() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.RESOLVED, assigneeId);

            patchStatus(ticket.getId(), assigneeToken, TicketStatus.CLOSED, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TICKET_INVALID_TRANSITION"));
        }

        @Test
        @DisplayName("toStatus=ASSIGNED 는 전이표상 허용이어도 이 API 에서는 409 — 배정 API 담당")
        void assignedIsHandledByAssignApi() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.IN_PROGRESS, assigneeId);

            patchStatus(ticket.getId(), leadToken, TicketStatus.ASSIGNED, null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("TICKET_INVALID_TRANSITION"))
                    .andExpect(jsonPath("$.error.message").value("담당자 변경은 배정 API 로 처리해 주세요."));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(historiesOf(ticket)).isEmpty();
        }

        @Test
        @DisplayName("없는 티켓은 404 TICKET_NOT_FOUND")
        void unknownTicket() throws Exception {
            patchStatus(9_999_999L, leadToken, TicketStatus.IN_PROGRESS, null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("권한")
    class Permission {

        @Test
        @DisplayName("담당자가 아닌 상담원은 403 TICKET_NOT_ASSIGNEE — 전이 자체는 허용이어도 막는다")
        void nonAssigneeAgentIsForbidden() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);

            patchStatus(ticket.getId(), otherAgentToken, TicketStatus.IN_PROGRESS, null)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNEE"));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(events.stream(TicketStatusChangedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("미배정 티켓을 상담원이 건드려도 403 — agentId 가 null 이면 담당자가 아니다")
        void unassignedTicketIsNotMine() {
            Ticket ticket = givenTicket(TicketStatus.RECEIVED, null);

            assertThatThrownBy(() -> ticketService.changeStatus(ticket.getId(),
                    TicketStatus.IN_PROGRESS, null, assigneeId, ActorRole.AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                            .isEqualTo("TICKET_NOT_ASSIGNEE"));
        }

        @Test
        @DisplayName("고객 토큰으로는 콘솔 API 에 접근할 수 없다")
        void customerCannotCallConsole() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);

            patchStatus(ticket.getId(), customerToken, TicketStatus.IN_PROGRESS, null)
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /{id}/histories — 이력 조회")
    class Histories {

        @Test
        @DisplayName("쌓인 순서대로 돌려주고 회원 수행자에는 이름이 붙는다")
        void returnsHistoriesWithActorName() throws Exception {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);
            ticketService.changeStatus(ticket.getId(), TicketStatus.IN_PROGRESS, "확인 시작", assigneeId,
                    ActorRole.AGENT);

            mockMvc.perform(get("/api/console/tickets/{id}/histories", ticket.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + assigneeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].action").value("STATUS_CHANGE"))
                    .andExpect(jsonPath("$.data[0].fromValue").value("ASSIGNED"))
                    .andExpect(jsonPath("$.data[0].toValue").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data[0].actorName").value("이상담"))
                    .andExpect(jsonPath("$.data[0].actorType").value("MEMBER"))
                    .andExpect(jsonPath("$.data[0].memo").value("확인 시작"));
        }

        @Test
        @DisplayName("SYSTEM 이력은 actorId 가 없으므로 actorName 도 null 이다")
        void systemHistoryHasNoActorName() {
            Ticket ticket = givenTicket(TicketStatus.RECEIVED, null);
            ticketHistoryRepository.save(TicketHistory.builder()
                    .ticketId(ticket.getId())
                    .action(HistoryAction.CREATE)
                    .toValue(TicketStatus.RECEIVED.name())
                    .actorType(ActorType.SYSTEM)
                    .build());

            List<TicketHistoryResponse> histories = ticketService.findHistories(ticket.getId());

            assertThat(histories).singleElement()
                    .satisfies(h -> {
                        assertThat(h.actorName()).isNull();
                        assertThat(h.actorType()).isEqualTo(ActorType.SYSTEM);
                    });
        }

        @Test
        @DisplayName("여러 건이면 created_at 오름차순으로 정렬된다")
        void sortedByCreatedAt() {
            Ticket ticket = givenTicket(TicketStatus.ASSIGNED, assigneeId);
            ticketService.changeStatus(ticket.getId(), TicketStatus.IN_PROGRESS, null, assigneeId,
                    ActorRole.AGENT);
            ticketService.changeStatus(ticket.getId(), TicketStatus.RESOLVED, null, assigneeId,
                    ActorRole.AGENT);

            List<TicketHistoryResponse> histories = ticketService.findHistories(ticket.getId());

            assertThat(histories).hasSize(2)
                    .isSortedAccordingTo(Comparator.comparing(TicketHistoryResponse::createdAt))
                    .extracting(TicketHistoryResponse::toValue)
                    .containsExactly("IN_PROGRESS", "RESOLVED");
        }

        @Test
        @DisplayName("없는 티켓의 이력은 404")
        void unknownTicket() throws Exception {
            mockMvc.perform(get("/api/console/tickets/{id}/histories", 9_999_999L)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + leadToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }
    }
}
