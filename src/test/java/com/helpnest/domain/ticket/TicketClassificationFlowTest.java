// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Sentiment;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.port.TicketClassificationPort;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.TicketClassificationService;
import com.helpnest.global.error.BusinessException;

/**
 * AI 분류 반영 + SLA 재계산 + 자동 배정 통합 테스트 (docs/05 §3.1, PRD 6.1·6.2).
 *
 * <p>SLA 기대값은 PRD 6.1 표에서 직접 옮겼다(NORMAL 1440분, URGENT 60분). 정책 테이블에서
 * 읽어 와 비교하면 계산식이 틀려도 통과하는 동어반복이 된다.
 *
 * <p>자동 배정 경로는 시드의 상담원 3명(agent1~3, available=true)을 그대로 쓴다.
 */
@SpringBootTest
@Transactional
@DisplayName("AI 분류 반영 흐름")
class TicketClassificationFlowTest {

    /** PRD 6.1 */
    private static final int NORMAL_MINUTES = 1440;
    private static final int URGENT_MINUTES = 60;

    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketHistoryRepository ticketHistoryRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    TicketClassificationService classificationService;
    @Autowired
    TicketClassificationPort classificationPort;

    private Ticket givenReceivedTicket() {
        return ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .customerId(memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId())
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다. 너무 늦네요.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(OffsetDateTime.now().plusMinutes(NORMAL_MINUTES))
                .build());
    }

    private long minutesFromCreatedAt(Ticket ticket) {
        return Duration.between(ticket.getCreatedAt(), ticket.getFirstResponseDueAt()).toMinutes();
    }

    private List<TicketHistory> historiesOf(Ticket ticket) {
        return ticketHistoryRepository.findAll().stream()
                .filter(h -> h.getTicketId().equals(ticket.getId()))
                .toList();
    }

    private Long agentId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    @Nested
    @DisplayName("AI 결과 반영")
    class AiResult {

        @Test
        @DisplayName("우선순위가 NORMAL → URGENT 면 기한이 1440분에서 60분으로 재계산된다")
        void recalculatesDueAtOnPriorityChange() {
            Ticket ticket = givenReceivedTicket();
            assertThat(minutesFromCreatedAt(ticket)).isBetween(NORMAL_MINUTES - 1L, (long) NORMAL_MINUTES);

            classificationService.applyAiResult(ticket.getId(), TicketCategory.DELIVERY,
                    TicketPriority.URGENT, Sentiment.NEGATIVE);

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.DELIVERY);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.URGENT);
            assertThat(ticket.getSentiment()).isEqualTo(Sentiment.NEGATIVE);
            assertThat(minutesFromCreatedAt(ticket)).isEqualTo(URGENT_MINUTES);
        }

        @Test
        @DisplayName("우선순위가 바뀌면 PRIORITY_CHANGE 이력 1건이 SYSTEM 으로 남는다")
        void writesPriorityChangeHistory() {
            Ticket ticket = givenReceivedTicket();

            classificationService.applyAiResult(ticket.getId(), TicketCategory.REFUND,
                    TicketPriority.HIGH, Sentiment.NEUTRAL);

            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.PRIORITY_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getFromValue()).isEqualTo("NORMAL");
                        assertThat(h.getToValue()).isEqualTo("HIGH");
                        assertThat(h.getActorType()).isEqualTo(ActorType.SYSTEM);
                        assertThat(h.getActorId()).isNull();
                    });
        }

        @Test
        @DisplayName("우선순위가 그대로면 기한을 다시 계산하지 않고 이력도 남기지 않는다")
        void skipsRecalculationWhenPriorityUnchanged() {
            Ticket ticket = givenReceivedTicket();
            OffsetDateTime before = ticket.getFirstResponseDueAt();

            classificationService.applyAiResult(ticket.getId(), TicketCategory.PAYMENT,
                    TicketPriority.NORMAL, Sentiment.NEUTRAL);

            assertThat(ticket.getFirstResponseDueAt()).isEqualTo(before);
            assertThat(historiesOf(ticket))
                    .noneMatch(h -> h.getAction() == HistoryAction.PRIORITY_CHANGE);
        }

        @Test
        @DisplayName("null 값은 '판정하지 않음'이라 기존 값을 유지한다")
        void keepsExistingValuesOnNull() {
            Ticket ticket = givenReceivedTicket();

            classificationService.applyAiResult(ticket.getId(), null, null, null);

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ETC);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.NORMAL);
            assertThat(ticket.getSentiment()).isNull();
        }
    }

    @Nested
    @DisplayName("포트 호출 (신수진 리스너 경로)")
    class PortEntry {

        @Test
        @DisplayName("분류 반영 후 자동 배정까지 이어진다")
        void appliesAndAssigns() {
            Ticket ticket = givenReceivedTicket();

            classificationPort.applyClassification(ticket.getId(), "DELIVERY", "URGENT", "NEGATIVE");

            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.URGENT);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(ticket.getAgentId()).isNotNull();
            assertThat(historiesOf(ticket)).extracting(TicketHistory::getAction)
                    .contains(HistoryAction.PRIORITY_CHANGE, HistoryAction.ASSIGN);
        }

        @Test
        @DisplayName("소문자·공백이 섞인 값도 받아 준다 — LLM 출력이 항상 깔끔하지는 않다")
        void acceptsLowerCaseAndWhitespace() {
            Ticket ticket = givenReceivedTicket();

            classificationPort.applyClassification(ticket.getId(), " delivery ", "urgent", "negative");

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.DELIVERY);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.URGENT);
        }

        @Test
        @DisplayName("enum 목록 밖 값은 TICKET_INVALID_CLASSIFICATION 으로 거부한다")
        void rejectsUnknownEnumValue() {
            Ticket ticket = givenReceivedTicket();

            assertThatThrownBy(() -> classificationPort.applyClassification(
                    ticket.getId(), "SPACE_TRAVEL", "URGENT", "NEGATIVE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                            .isEqualTo("TICKET_INVALID_CLASSIFICATION"));

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ETC);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
        }

        @Test
        @DisplayName("분류 실패는 기본값을 유지하고 배정만 진행한다 (docs/05 §3.1 7번)")
        void failedClassificationStillAssigns() {
            Ticket ticket = givenReceivedTicket();

            classificationPort.applyClassificationFailed(ticket.getId());

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ETC);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.NORMAL);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(historiesOf(ticket))
                    .noneMatch(h -> h.getAction() == HistoryAction.PRIORITY_CHANGE);
        }
    }

    @Nested
    @DisplayName("상담원 수동 분류 수정")
    class ManualUpdate {

        @Test
        @DisplayName("담당자가 아닌 상담원은 TICKET_NOT_ASSIGNEE")
        void rejectsNonAssignee() {
            Ticket ticket = givenReceivedTicket();
            ticket.assignTo(agentId("agent1@helpnest.local"), OffsetDateTime.now());
            Long otherAgent = agentId("agent2@helpnest.local");

            assertThatThrownBy(() -> classificationService.applyManualUpdate(ticket.getId(),
                    TicketCategory.REFUND, TicketPriority.URGENT, otherAgent, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                            .isEqualTo("TICKET_NOT_ASSIGNEE"));

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ETC);
        }

        @Test
        @DisplayName("담당 상담원 본인은 수정할 수 있고 유형·우선순위 이력이 각각 남는다")
        void allowsAssignee() {
            Ticket ticket = givenReceivedTicket();
            Long assignee = agentId("agent1@helpnest.local");
            ticket.assignTo(assignee, OffsetDateTime.now());

            classificationService.applyManualUpdate(ticket.getId(), TicketCategory.REFUND,
                    TicketPriority.URGENT, assignee, false);

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.REFUND);
            assertThat(minutesFromCreatedAt(ticket)).isEqualTo(URGENT_MINUTES);
            assertThat(historiesOf(ticket)).extracting(TicketHistory::getAction)
                    .contains(HistoryAction.CATEGORY_CHANGE, HistoryAction.PRIORITY_CHANGE);
            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.CATEGORY_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getActorType()).isEqualTo(ActorType.MEMBER);
                        assertThat(h.getActorId()).isEqualTo(assignee);
                    });
        }

        @Test
        @DisplayName("LEAD 는 담당자가 아니어도 수정할 수 있다")
        void allowsLeadOnAnyTicket() {
            Ticket ticket = givenReceivedTicket();
            ticket.assignTo(agentId("agent1@helpnest.local"), OffsetDateTime.now());
            Long lead = agentId("lead@helpnest.local");

            classificationService.applyManualUpdate(ticket.getId(), TicketCategory.EXCHANGE,
                    TicketPriority.LOW, lead, true);

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.EXCHANGE);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.LOW);
        }

        @Test
        @DisplayName("감정은 수동 수정 대상이 아니라 그대로 남는다")
        void keepsSentiment() {
            Ticket ticket = givenReceivedTicket();
            Long lead = agentId("lead@helpnest.local");
            classificationService.applyAiResult(ticket.getId(), TicketCategory.DELIVERY,
                    TicketPriority.NORMAL, Sentiment.NEGATIVE);

            classificationService.applyManualUpdate(ticket.getId(), TicketCategory.REFUND,
                    TicketPriority.NORMAL, lead, true);

            assertThat(ticket.getSentiment()).isEqualTo(Sentiment.NEGATIVE);
        }
    }
}
