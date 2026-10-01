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
import com.helpnest.domain.ticket.dto.CustomerReplyCreateRequest;
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
        return toResponse(reply, ticket);
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
    private ReplyResponse toResponse(TicketReply reply, Ticket ticket) {
        List<TicketAttachmentResponse> attachments = attachmentLookupRepository
                .findByReplyId(reply.getId()).stream()
                .map(row -> new TicketAttachmentResponse(row.getAttachmentId(), row.getOriginalName(),
                        row.getSize()))
                .toList();

        return new ReplyResponse(reply.getId(), reply.getWriterType(), writerNameOf(reply, ticket),
                reply.getContent(), reply.isInternal(), attachments, reply.getCreatedAt());
    }

    /**
     * 작성자 이름. 비회원(GUEST)은 writer_id 가 없으므로 티켓에 적힌 비회원 이름을 쓴다 —
     * 회원 조회로는 이름을 찾을 수 없어 null 이 되고, 화면에 작성자가 빈칸으로 나간다.
     */
    private String writerNameOf(TicketReply reply, Ticket ticket) {
        return reply.getWriterType() == WriterType.GUEST
                ? ticket.getGuestName()
                : memberNameLookupRepository.findName(reply.getWriterId());
    }

    /**
     * 고객의 추가 답글 (POST /api/tickets/{id}/replies, FR-INQ-06).
     *
     * <h2>RESOLVED 에서 답글이 달리면 재문의로 되돌린다</h2>
     * 전이표(PRD 5장)의 {@code RESOLVED → IN_PROGRESS} 수행자가 SYSTEM·CUSTOMER 이므로
     * {@code actorRole} 을 CUSTOMER 로 넘겨야 통과한다. 해결됐다고 본 건에 고객이 다시 말을
     * 걸었다는 뜻이므로 상담원의 처리 중 목록에 다시 올라와야 한다.
     *
     * <h2>CLOSED 에는 답글을 막는다</h2>
     * 종료된 티켓은 상태가 불변이고(전이표에서 CLOSED 는 비어 있다) 담당자도 손을 뗀 상태다.
     * 답글만 쌓이면 아무도 보지 않는 글이 되므로 {@code TICKET_ALREADY_CLOSED}(409) 로 거절하고
     * 새 문의를 받는다.
     *
     * <h2>내부 메모는 만들 수 없다</h2>
     * {@code isInternal} 을 요청에서 받지 않고 false 로 고정한다
     * ({@code CustomerReplyCreateRequest} 주석) — 받아서 무시하는 대신 아예 두지 않았다.
     *
     * @param customerId 회원이면 member_id, 비회원이면 null
     * @throws BusinessException TICKET_NOT_FOUND(404, 남의 티켓도 같은 응답),
     *                           TICKET_ALREADY_CLOSED(409)
     */
    @Transactional
    public ReplyResponse addCustomerReply(Long ticketId, CustomerReplyCreateRequest req,
            Long customerId) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));

        // 남의 티켓은 존재 여부를 알리지 않기 위해 404 다(CustomerTicketService 주석과 같은 이유)
        if (!Objects.equals(ticket.getCustomerId(), customerId)) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new BusinessException(TicketErrorCode.ALREADY_CLOSED);
        }
        if (req.content().length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(TicketErrorCode.CONTENT_TOO_LONG);
        }

        WriterType writerType = customerId != null ? WriterType.CUSTOMER : WriterType.GUEST;
        TicketReply reply = ticketReplyRepository.save(TicketReply.builder()
                .ticketId(ticketId)
                .writerId(customerId)
                .writerType(writerType)
                .content(req.content())
                .isInternal(false)
                .build());

        linkAttachments(req.attachmentIds(), ticketId, reply.getId());

        if (ticket.getStatus() == TicketStatus.RESOLVED) {
            ticketService.changeStatus(ticketId, TicketStatus.IN_PROGRESS, "고객 재문의", customerId,
                    ActorRole.CUSTOMER);
        }

        eventPublisher.publishEvent(
                new ReplyCreatedEvent(ticketId, reply.getId(), writerType.name(), false));
        log.info("[reply] 고객 답글 ticketNo={} replyId={} writerType={}", ticket.getTicketNo(),
                reply.getId(), writerType);
        return toResponse(reply, ticket);
    }
}
