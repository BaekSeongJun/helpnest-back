// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.dto.ReplyResponse;
import com.helpnest.domain.ticket.dto.TicketAttachmentResponse;
import com.helpnest.domain.ticket.dto.TicketDetailResponse;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.MemberName;
import com.helpnest.domain.ticket.repository.MemberNameLookupRepository;
import com.helpnest.domain.ticket.repository.TicketAttachmentLookupRepository;
import com.helpnest.domain.ticket.repository.TicketAttachmentLookupRepository.AttachmentRow;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 고객용 티켓 조회 (docs/04 §7, 화면 CU-07 상세·CU-08 목록).
 *
 * <h2>내부 메모가 절대 섞이지 않게 하는 방법</h2>
 * 답변 목록은 {@code findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc} 로만 읽는다.
 * 전체를 읽어 와 변환 단계에서 거르는 방식은 한 번만 빠뜨려도 응답 본문에 유출되므로,
 * 조건을 쿼리 메서드 이름에 못박아 호출부가 빠뜨릴 수 없게 했다
 * ({@code TicketDetailResponse} 의 "replies 에 내부 메모를 넣지 않는 경우" 주석).
 *
 * <h2>남의 티켓에 404 를 주는 이유</h2>
 * 소유자가 아니면 {@code TICKET_NOT_FOUND} 다. 403 을 주면 "그 티켓은 있지만 네 것이 아니다"가
 * 되어 id 를 순회하며 실재하는 티켓 번호를 확인할 수 있다. 없는 티켓과 남의 티켓의 응답이
 * 같아야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerTicketService {

    /**
     * 본문 첨부를 담는 맵 키. 실제 reply_id 와 겹치지 않는 음수를 쓴다.
     * {@code HashMap} 은 null 키를 허용하지만 {@code Map.of()} 로 대체될 수 있는 자리에서는
     * null 키 조회가 NPE 이므로 null 을 키로 쓰지 않는다.
     */
    private static final Long TICKET_BODY = -1L;

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final TicketAttachmentLookupRepository attachmentLookupRepository;
    private final MemberNameLookupRepository memberNameLookupRepository;

    /**
     * 회원 고객의 문의 목록 (GET /api/tickets/my).
     *
     * <p>이름은 페이지 전체의 회원 id 를 모아 <b>한 번에</b> 조회한다. 행마다 조회하면 20행에
     * 20번 쿼리가 나간다({@code TicketListItemResponse} 의 N+1 주석과 같은 이유).
     *
     * <p>비회원은 이 API 를 쓸 수 없다 — Guest 토큰은 티켓 1건에만 유효하므로 "내 문의 목록"이
     * 성립하지 않고, 컨트롤러의 {@code JwtProvider.memberId} 가 Guest 토큰을 403 으로 막는다.
     */
    public PageResponse<TicketListItemResponse> findMyTickets(Long customerId, Pageable pageable) {
        Page<Ticket> page = ticketRepository.findByCustomerId(customerId, pageable);
        Map<Long, String> names = namesOf(page.getContent(), List.of());
        return PageResponse.from(page.map(t -> toListItem(t, names)));
    }

    /**
     * 회원 고객의 문의 상세 (GET /api/tickets/{id}).
     *
     * @throws BusinessException 없는 티켓이거나 <b>남의 티켓</b>이면 TICKET_NOT_FOUND
     */
    public TicketDetailResponse findMyTicket(Long ticketId, Long customerId) {
        Ticket ticket = load(ticketId);
        if (!Objects.equals(ticket.getCustomerId(), customerId)) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        return toDetail(ticket);
    }

    /**
     * 비회원 고객의 문의 상세 (GET /api/tickets/{id} + Guest 토큰).
     *
     * <p>토큰의 {@code ticketId} 클레임과 경로 {@code {id}} 가 같은지는 컨트롤러가 이미 확인했다.
     * 여기서는 <b>회원 티켓이 아닌지</b>를 한 번 더 본다 — Guest 토큰은 비회원 티켓에만 발급되지만
     * (백성준 {@code POST /api/auth/guest}), 발급 조건이 바뀌어도 회원 티켓이 비회원 경로로
     * 새지 않게 하는 두 번째 방어선이다.
     */
    public TicketDetailResponse findGuestTicket(Long ticketId) {
        Ticket ticket = load(ticketId);
        if (ticket.isMemberTicket()) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        return toDetail(ticket);
    }

    private Ticket load(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
    }

    private TicketListItemResponse toListItem(Ticket t, Map<Long, String> names) {
        return new TicketListItemResponse(t.getId(), t.getTicketNo(), t.getTitle(),
                t.getCustomerId(), customerNameOf(t, names), t.getCategory(), t.getPriority(),
                t.getSentiment(), t.getStatus(), t.getAgentId(), nameOf(names, t.getAgentId()),
                t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.isSlaWarned(),
                t.isSlaBreached(), t.getCreatedAt());
    }

    /**
     * 상세 응답. 첨부는 티켓 단위로 한 번에 읽어 본문 첨부({@code replyId == null})와 답글 첨부를
     * 나눈다 — 답글마다 조회하면 N+1 이 된다.
     */
    private TicketDetailResponse toDetail(Ticket t) {
        List<TicketReply> replies = ticketReplyRepository
                .findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc(t.getId());
        Map<Long, List<TicketAttachmentResponse>> attachments = attachmentsOf(t.getId());
        Map<Long, String> names = namesOf(List.of(t), replies);

        List<ReplyResponse> replyResponses = replies.stream()
                .map(r -> new ReplyResponse(r.getId(), r.getWriterType(), writerNameOf(r, t, names),
                        r.getContent(), r.isInternal(),
                        attachments.getOrDefault(r.getId(), List.of()), r.getCreatedAt()))
                .toList();

        return new TicketDetailResponse(t.getId(), t.getTicketNo(), t.getTitle(), t.getCustomerId(),
                customerNameOf(t, names), t.getCategory(), t.getPriority(), t.getSentiment(),
                t.getStatus(), t.getAgentId(), nameOf(names, t.getAgentId()),
                t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.isSlaWarned(),
                t.isSlaBreached(), t.getCreatedAt(), t.getContent(), t.getChannel(),
                attachments.getOrDefault(TICKET_BODY, List.of()), replyResponses,
                t.getAssignedAt(), t.getResolvedAt(), t.getClosedAt(), t.getUpdatedAt());
    }

    /** 티켓의 첨부를 {@code reply_id} 로 묶는다. 본문 첨부는 {@link #TICKET_BODY} 키에 모은다 */
    private Map<Long, List<TicketAttachmentResponse>> attachmentsOf(Long ticketId) {
        Map<Long, List<TicketAttachmentResponse>> grouped = new HashMap<>();
        for (AttachmentRow row : attachmentLookupRepository.findByTicketId(ticketId)) {
            Long key = row.getReplyId() == null ? TICKET_BODY : row.getReplyId();
            grouped.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new TicketAttachmentResponse(row.getAttachmentId(), row.getOriginalName(),
                            row.getSize()));
        }
        return grouped;
    }

    /** 티켓과 답변에 등장하는 회원 id 전부를 한 번에 조회한다(이력 조회와 같은 경로) */
    private Map<Long, String> namesOf(List<Ticket> tickets, List<TicketReply> replies) {
        Set<Long> ids = new LinkedHashSet<>();
        tickets.forEach(t -> {
            ids.add(t.getCustomerId());
            ids.add(t.getAgentId());
        });
        replies.forEach(r -> ids.add(r.getWriterId()));
        ids.remove(null);

        if (ids.isEmpty()) {
            return Map.of();
        }
        return memberNameLookupRepository.findNames(ids).stream()
                .collect(Collectors.toMap(MemberName::getMemberId, MemberName::getName));
    }

    /** 회원이면 회원 이름, 비회원이면 접수 시 입력한 이름 */
    private static String customerNameOf(Ticket t, Map<Long, String> names) {
        return t.isMemberTicket() ? nameOf(names, t.getCustomerId()) : t.getGuestName();
    }

    /**
     * 답변 작성자 이름. GUEST 는 writer_id 가 없으므로 티켓의 비회원 이름을 쓰고,
     * SYSTEM 은 사람이 아니라 null 이다({@code ReplyResponse} 주석).
     */
    private static String writerNameOf(TicketReply r, Ticket t, Map<Long, String> names) {
        return switch (r.getWriterType()) {
            case GUEST -> t.getGuestName();
            case SYSTEM -> null;
            default -> nameOf(names, r.getWriterId());
        };
    }

    /** {@code Map.of()} 는 null 키 조회에 NPE 를 던지므로 맵에 묻기 전에 거른다 */
    private static String nameOf(Map<Long, String> names, Long memberId) {
        return memberId == null ? null : names.get(memberId);
    }
}
