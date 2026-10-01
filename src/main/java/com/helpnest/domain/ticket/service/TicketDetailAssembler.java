// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.helpnest.domain.ticket.dto.ReplyResponse;
import com.helpnest.domain.ticket.dto.TicketAttachmentResponse;
import com.helpnest.domain.ticket.dto.TicketDetailResponse;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.repository.MemberName;
import com.helpnest.domain.ticket.repository.MemberNameLookupRepository;
import com.helpnest.domain.ticket.repository.TicketAttachmentLookupRepository;
import com.helpnest.domain.ticket.repository.TicketAttachmentLookupRepository.AttachmentRow;

import lombok.RequiredArgsConstructor;

/**
 * 티켓 → 응답 DTO 변환. 고객용({@link CustomerTicketService})과 콘솔용
 * ({@link ConsoleTicketService})이 같은 모양을 내려주므로 변환을 한 곳에 둔다.
 *
 * <h2>내부 메모 필터링을 여기서 하지 않는 이유</h2>
 * 답변 목록을 <b>인자로 받는다</b>. 어떤 답변을 보여 줄지는 호출자가 쿼리 단계에서 정하고
 * (고객용은 {@code findByTicketIdAndIsInternalFalse...}, 콘솔용은 전체), 이 클래스는 받은
 * 것을 그대로 담는다. 여기서 플래그로 분기하면 "플래그를 잘못 넘기면 유출"이 되므로,
 * 선택 자체를 쿼리 메서드 이름에 못박아 둔 호출자 책임으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class TicketDetailAssembler {

    /** 본문 첨부를 담는 맵 키. 실제 reply_id 와 겹치지 않는 음수를 쓴다 */
    private static final Long TICKET_BODY = -1L;

    private final TicketAttachmentLookupRepository attachmentLookupRepository;
    private final MemberNameLookupRepository memberNameLookupRepository;

    /**
     * 상세 응답. 첨부는 티켓 단위로 한 번에 읽어 본문 첨부({@code replyId == null})와 답글
     * 첨부를 나눈다 — 답글마다 조회하면 N+1 이 된다.
     *
     * @param replies 응답에 담을 답변. <b>내부 메모 포함 여부는 호출자가 쿼리로 결정한다</b>
     */
    public TicketDetailResponse toDetail(Ticket t, List<TicketReply> replies) {
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

    /**
     * 목록 한 행. 이름 맵을 인자로 받는 이유는 호출자가 <b>페이지 전체의 이름을 한 번에</b>
     * 조회해 넘기기 때문이다 — 행마다 조회하면 20행에 20쿼리다
     * ({@code TicketListItemResponse} 의 N+1 주석).
     */
    public TicketListItemResponse toListItem(Ticket t, Map<Long, String> names) {
        return new TicketListItemResponse(t.getId(), t.getTicketNo(), t.getTitle(),
                t.getCustomerId(), customerNameOf(t, names), t.getCategory(), t.getPriority(),
                t.getSentiment(), t.getStatus(), t.getAgentId(), nameOf(names, t.getAgentId()),
                t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.isSlaWarned(),
                t.isSlaBreached(), t.getCreatedAt());
    }

    /** 티켓과 답변에 등장하는 회원 id 전부를 한 번에 조회한다(이력 조회와 같은 경로) */
    public Map<Long, String> namesOf(List<Ticket> tickets, List<TicketReply> replies) {
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
