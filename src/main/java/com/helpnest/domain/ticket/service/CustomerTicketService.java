// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.dto.TicketDetailResponse;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.error.TicketErrorCode;
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

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final TicketDetailAssembler assembler;

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
        Map<Long, String> names = assembler.namesOf(page.getContent(), List.of());
        return PageResponse.from(page.map(t -> assembler.toListItem(t, names)));
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

    /**
     * 고객용 상세. 답변은 <b>내부 메모 제외 쿼리</b>로만 읽는다 — 어떤 답변을 보여 줄지는
     * 어셈블러가 아니라 여기서 쿼리로 결정한다({@link TicketDetailAssembler} 주석).
     */
    private TicketDetailResponse toDetail(Ticket ticket) {
        return assembler.toDetail(ticket, ticketReplyRepository
                .findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc(ticket.getId()));
    }

    private Ticket load(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
    }

}
