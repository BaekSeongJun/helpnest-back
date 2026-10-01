// @owner PMJ
package com.helpnest.domain.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.attachment.entity.Attachment;
import com.helpnest.domain.attachment.repository.AttachmentRepository;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.event.ReplyCreatedEvent;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.security.JwtProvider;

/**
 * 상담원 답변·내부 메모 통합 테스트 (docs/04 §7, FR-TKT-04, PRD 6.1).
 *
 * <p>검증의 중심은 <b>공개 답변과 내부 메모의 부수 효과 차이</b>다. 저장은 둘 다 되지만
 * 첫 응답 시각·상태 전이·이력은 공개 답변에만 따라붙는다. 내부 메모가 SLA 를 충족시키면
 * 기한 지표가 실제 고객 대응과 무관해지므로, 이 경계가 깨지는 쪽이 조용한 버그다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
@DisplayName("POST /api/console/tickets/{id}/replies — 상담원 답변·내부 메모")
class ConsoleReplyApiTest {

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
    AttachmentRepository attachmentRepository;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    ApplicationEvents events;

    private Long customerId;
    private Long assigneeId;
    private String assigneeToken;
    private String otherAgentToken;
    private String leadToken;

    @BeforeEach
    void loginAsSeedMembers() {
        customerId = memberId("customer1@helpnest.local");
        assigneeId = memberId("agent1@helpnest.local");

        assigneeToken = jwtProvider.createAccessToken(assigneeId, "AGENT");
        otherAgentToken = jwtProvider.createAccessToken(memberId("agent2@helpnest.local"), "AGENT");
        leadToken = jwtProvider.createAccessToken(memberId("lead@helpnest.local"), "LEAD");
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    /** 담당자가 붙은 ASSIGNED 티켓. assignTo 가 상태까지 ASSIGNED 로 바꾼다 */
    private Ticket givenAssignedTicket() {
        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .customerId(customerId)
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build());
        ticket.assignTo(assigneeId, OffsetDateTime.now());
        return ticket;
    }

    private ResultActions postReply(Long ticketId, String token, String content, Boolean isInternal)
            throws Exception {
        String body = "{\"content\":\"%s\"%s}".formatted(content,
                isInternal == null ? "" : ",\"isInternal\":%s".formatted(isInternal));
        return mockMvc.perform(post("/api/console/tickets/{id}/replies", ticketId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * 업로드만 된(ticket_id NULL) 첨부 1건. 포트의 연결 조건이 첨부의 uploaded_by 와 답글의
     * writer_id 가 같을 것을 요구하므로 작성자를 맞춰 둔다.
     */
    private Long givenUploadedAttachment(Long uploaderId) {
        return attachmentRepository.save(Attachment.builder()
                .originalName("배송사진.png")
                .storedKey("test/%d.png".formatted(System.nanoTime()))
                .contentType("image/png")
                .sizeBytes(1_024L)
                .uploadedBy(uploaderId)
                .build()).getId();
    }

    private List<TicketReply> repliesOf(Ticket ticket) {
        return ticketReplyRepository.findAll().stream()
                .filter(r -> r.getTicketId().equals(ticket.getId()))
                .toList();
    }

    private List<TicketHistory> historiesOf(Ticket ticket) {
        return ticketHistoryRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId());
    }

    @Nested
    @DisplayName("공개 답변")
    class PublicReply {

        @Test
        @DisplayName("첫 응답 시각이 기록되고 ASSIGNED → IN_PROGRESS 로 전이된다")
        void recordsFirstResponseAndTransitions() throws Exception {
            Ticket ticket = givenAssignedTicket();
            assertThat(ticket.getFirstRespondedAt()).isNull();

            postReply(ticket.getId(), assigneeToken, "확인했습니다. 오늘 재발송해 드릴게요.", false)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.replyId").isNumber())
                    .andExpect(jsonPath("$.data.writerType").value("AGENT"))
                    .andExpect(jsonPath("$.data.writerName").value("이상담"))
                    .andExpect(jsonPath("$.data.isInternal").value(false))
                    .andExpect(jsonPath("$.data.attachments").isEmpty());

            assertThat(ticket.getFirstRespondedAt()).isNotNull();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("전이가 changeStatus 를 거치므로 STATUS_CHANGE 이력과 이벤트가 함께 남는다")
        void reusesChangeStatus() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "처리 시작합니다.", false)
                    .andExpect(status().isCreated());

            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .singleElement()
                    .satisfies(h -> {
                        assertThat(h.getFromValue()).isEqualTo("ASSIGNED");
                        assertThat(h.getToValue()).isEqualTo("IN_PROGRESS");
                        assertThat(h.getActorId()).isEqualTo(assigneeId);
                    });
            assertThat(events.stream(TicketStatusChangedEvent.class)).hasSize(1);
            assertThat(events.stream(ReplyCreatedEvent.class))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.writerType()).isEqualTo("AGENT");
                        assertThat(e.isInternal()).isFalse();
                    });
        }

        @Test
        @DisplayName("두 번째 답변은 first_responded_at 을 덮어쓰지 않는다 — 첫 응답은 한 번만 성립한다")
        void keepsFirstResponseOnSecondReply() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "확인 중입니다.", false)
                    .andExpect(status().isCreated());
            OffsetDateTime firstAt = ticket.getFirstRespondedAt();
            assertThat(firstAt).isNotNull();

            postReply(ticket.getId(), assigneeToken, "재발송 완료했습니다.", false)
                    .andExpect(status().isCreated());

            assertThat(ticket.getFirstRespondedAt()).isEqualTo(firstAt);
            assertThat(repliesOf(ticket)).hasSize(2);
            // 이미 IN_PROGRESS 라 전이할 것이 없으므로 이력도 늘지 않는다
            assertThat(historiesOf(ticket))
                    .filteredOn(h -> h.getAction() == HistoryAction.STATUS_CHANGE)
                    .hasSize(1);
        }

        @Test
        @DisplayName("첨부를 함께 보내면 답글에 연결되고 응답에 이름·크기가 담긴다")
        void linksAttachments() throws Exception {
            Ticket ticket = givenAssignedTicket();
            Long attachmentId = givenUploadedAttachment(assigneeId);

            mockMvc.perform(post("/api/console/tickets/{id}/replies", ticket.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + assigneeToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"content":"사진 확인 부탁드립니다.","isInternal":false,"attachmentIds":[%d]}
                            """.formatted(attachmentId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.attachments", hasSize(1)))
                    .andExpect(jsonPath("$.data.attachments[0].attachmentId").value(attachmentId))
                    .andExpect(jsonPath("$.data.attachments[0].originalName").value("배송사진.png"))
                    .andExpect(jsonPath("$.data.attachments[0].size").value(1_024));
        }

        @Test
        @DisplayName("LEAD 는 담당자가 아니어도 답변할 수 있다")
        void leadCanReplyToOthersTicket() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), leadToken, "팀장이 대신 답변합니다.", false)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.writerName").value("김팀장"));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("내부 메모")
    class InternalNote {

        @Test
        @DisplayName("저장은 되지만 첫 응답 시각·상태·이력을 건드리지 않는다")
        void hasNoSideEffects() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "고객 이력 확인 필요. 지난달 환불 건 있음.", true)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.isInternal").value(true));

            assertThat(repliesOf(ticket)).singleElement()
                    .satisfies(r -> {
                        assertThat(r.isInternal()).isTrue();
                        assertThat(r.getWriterType()).isEqualTo(WriterType.AGENT);
                    });
            assertThat(ticket.getFirstRespondedAt()).isNull();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(historiesOf(ticket)).isEmpty();
            assertThat(events.stream(TicketStatusChangedEvent.class)).isEmpty();
        }

        @Test
        @DisplayName("ReplyCreatedEvent 는 발행되지만 isInternal=true 로 나간다 — 구독자가 고객 알림을 건너뛴다")
        void publishesEventWithInternalFlag() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "내부 공유용 메모", true)
                    .andExpect(status().isCreated());

            assertThat(events.stream(ReplyCreatedEvent.class))
                    .singleElement()
                    .satisfies(e -> assertThat(e.isInternal()).isTrue());
        }

        @Test
        @DisplayName("내부 메모 뒤에 공개 답변을 달면 그때 첫 응답 시각이 기록된다")
        void publicReplyAfterNoteStillRecords() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "메모만 먼저", true).andExpect(status().isCreated());
            assertThat(ticket.getFirstRespondedAt()).isNull();

            postReply(ticket.getId(), assigneeToken, "고객님께 안내드립니다.", false)
                    .andExpect(status().isCreated());

            assertThat(ticket.getFirstRespondedAt()).isNotNull();
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        }
    }

    @Nested
    @DisplayName("권한과 입력 검증")
    class Validation {

        @Test
        @DisplayName("담당자가 아닌 상담원은 403 TICKET_NOT_ASSIGNEE — 내부 메모도 막는다")
        void nonAssigneeIsForbidden() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), otherAgentToken, "남의 티켓에 메모", true)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_ASSIGNEE"));

            assertThat(repliesOf(ticket)).isEmpty();
        }

        @Test
        @DisplayName("5,001자는 400 — DTO @Size 가 먼저 거른다")
        void rejectsTooLongContent() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "가".repeat(5_001), false)
                    .andExpect(status().isBadRequest());

            assertThat(repliesOf(ticket)).isEmpty();
        }

        @Test
        @DisplayName("5,000자는 통과한다 — 경계값")
        void acceptsBoundaryLength() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "가".repeat(5_000), false)
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("isInternal 을 빠뜨리면 400 — 공개 답변으로 조용히 처리하면 메모가 유출된다")
        void rejectsMissingIsInternal() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "플래그 없는 요청", null)
                    .andExpect(status().isBadRequest());

            assertThat(repliesOf(ticket)).isEmpty();
        }

        @Test
        @DisplayName("빈 본문은 400")
        void rejectsBlankContent() throws Exception {
            Ticket ticket = givenAssignedTicket();

            postReply(ticket.getId(), assigneeToken, "   ", false)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("없는 티켓은 404")
        void unknownTicket() throws Exception {
            postReply(9_999_999L, leadToken, "답변", false)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_NOT_FOUND"));
        }
    }
}
