// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.attachment.port.AttachmentPort;
import com.helpnest.domain.ticket.dto.ConsoleReplyCreateRequest;
import com.helpnest.domain.ticket.dto.ReplyResponse;
import com.helpnest.domain.ticket.dto.TicketAttachmentResponse;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.event.ReplyCreatedEvent;
import com.helpnest.domain.ticket.repository.MemberNameLookupRepository;
import com.helpnest.domain.ticket.repository.TicketAttachmentLookupRepository;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 답변·내부 메모 등록 (docs/04 §7, FR-TKT-04).
 *
 * <h2>공개 답변과 내부 메모를 한 엔드포인트로 받는 이유</h2>
 * 둘은 저장 위치({@code ticket_reply})와 작성 흐름이 같고 {@code is_internal} 플래그만 다르다.
 * 다만 <b>부수 효과는 전혀 다르다</b> — 공개 답변만 SLA 의 첫 응답 시각을 기록하고 상태를
 * 전이시킨다. 내부 메모는 상담원끼리 남기는 기록이므로 고객 대응이 일어난 것이 아니고,
 * 메모를 남겼다고 SLA 가 충족되면 기한 지표가 실제 대응과 무관해진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketReplyService {

    /** docs/04 §7 요청 본문 제약. DTO 의 {@code @Size} 와 이중 방어다 */
    private static final int MAX_CONTENT_LENGTH = 5_000;

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final TicketAttachmentLookupRepository attachmentLookupRepository;
    private final MemberNameLookupRepository memberNameLookupRepository;
    private final TicketService ticketService;
    private final AttachmentPort attachmentPort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 상담원의 공개 답변 또는 내부 메모를 등록한다.
     *
     * <p>공개 답변일 때만 두 가지가 따라온다.
     * <ul>
     *   <li><b>첫 응답 시각</b> — {@code Ticket.markFirstResponded} 가 이미 기록된 값을 무시하므로
     *       답변을 여러 번 달아도 최초 시각이 덮이지 않는다(SLA 판정 기준이 흔들리면 안 된다).</li>
     *   <li><b>ASSIGNED → IN_PROGRESS 전이</b> — 엔티티의 {@code changeStatusTo} 를 직접 부르지 않고
     *       {@link TicketService#changeStatus} 를 재사용한다. 직접 부르면 전이 검증·STATUS_CHANGE
     *       이력·TicketStatusChangedEvent 세 가지가 조용히 빠지고, 같은 전이가 두 경로로 갈라진다.
     *       이미 IN_PROGRESS 이상이면 전이할 것이 없으므로 호출하지 않는다.</li>
     * </ul>
     *
     * <p>담당자 검증은 {@link TicketService#changeStatus} 와 같은 이유로 여기서도 한다 —
     * 내부 메모는 전이를 일으키지 않아 changeStatus 를 거치지 않으므로, 이 검증이 없으면
     * 상담원이 남의 티켓에 메모를 남길 수 있다.
     *
     * @param actorId   작성자의 member_id
     * @param actorRole 작성자 역할. AGENT 면 본인 담당 티켓만 허용한다
     * @throws BusinessException TICKET_NOT_FOUND(404), TICKET_NOT_ASSIGNEE(403),
     *                           TICKET_CONTENT_TOO_LONG(400)
     */
    @Transactional
    public ReplyResponse addAgentReply(Long ticketId, ConsoleReplyCreateRequest req, Long actorId,
            ActorRole actorRole) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));

        if (actorRole == ActorRole.AGENT && !Objects.equals(ticket.getAgentId(), actorId)) {
            throw new BusinessException(TicketErrorCode.NOT_ASSIGNEE);
        }
        if (req.content().length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(TicketErrorCode.CONTENT_TOO_LONG);
        }

        // DTO 가 @NotNull 로 막지만 서비스를 직접 부르는 경로에서는 null 일 수 있다
        boolean internal = Boolean.TRUE.equals(req.isInternal());

        TicketReply reply = ticketReplyRepository.save(TicketReply.builder()
                .ticketId(ticketId)
                .writerId(actorId)
                .writerType(WriterType.AGENT)
                .content(req.content())
                .isInternal(internal)
                .aiDraftId(req.aiDraftId())
                .build());

        linkAttachments(req.attachmentIds(), ticketId, reply.getId());

        if (!internal) {
            ticket.markFirstResponded(OffsetDateTime.now());
            if (ticket.getStatus() == TicketStatus.ASSIGNED) {
                ticketService.changeStatus(ticketId, TicketStatus.IN_PROGRESS, null, actorId, actorRole);
            }
        }

        eventPublisher.publishEvent(
                new ReplyCreatedEvent(ticketId, reply.getId(), WriterType.AGENT.name(), internal));
        log.info("[reply] 상담원 {} ticketNo={} replyId={} by={}", internal ? "내부 메모" : "공개 답변",
                ticket.getTicketNo(), reply.getId(), actorId);
        return toResponse(reply);
    }

    /**
     * 업로드만 돼 있던 첨부를 이 답글에 연결한다. 소유권 검증(남의 첨부인지, 이미 연결됐는지)은
     * 포트 구현의 책임이다(docs/04 §3 {@code ATTACHMENT_LINK_DENIED}).
     */
    private void linkAttachments(List<Long> attachmentIds, Long ticketId, Long replyId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        attachmentPort.linkToTicket(attachmentIds, ticketId, replyId);
    }

    /**
     * 작성자 이름은 이력 조회와 같은 경로로 읽는다({@link MemberNameLookupRepository}).
     * 첨부는 방금 연결한 것을 다시 조회한다 — 포트가 연결 결과를 돌려주지 않고, 실제로 연결된
     * 것만 응답에 담아야 하기 때문이다.
     */
    private ReplyResponse toResponse(TicketReply reply) {
        List<TicketAttachmentResponse> attachments = attachmentLookupRepository
                .findByReplyId(reply.getId()).stream()
                .map(row -> new TicketAttachmentResponse(row.getAttachmentId(), row.getOriginalName(),
                        row.getSize()))
                .toList();

        return new ReplyResponse(reply.getId(), reply.getWriterType(),
                memberNameLookupRepository.findName(reply.getWriterId()), reply.getContent(),
                reply.isInternal(), attachments, reply.getCreatedAt());
    }
}
