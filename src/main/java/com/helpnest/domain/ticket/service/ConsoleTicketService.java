// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.dto.TicketDetailResponse;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.dto.TicketSearchCondition;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.repository.TicketSpecs;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 상담 콘솔 티켓 조회 (docs/04 §7, 화면 CS-01 티켓함·CS-02 상세).
 *
 * <h2>AGENT 가 남의 티켓 목록을 볼 수 없게 하는 방법</h2>
 * 역할이 AGENT 면 {@code agentId} 조건을 <b>본인으로 덮어쓴다</b>
 * ({@link TicketSearchCondition#restrictedTo}). 파라미터를 검증해 거부하는 방식이 아니라
 * 조건을 강제하는 방식인 이유는, 거부(403)가 "그 상담원이 존재한다"는 신호가 되고 검증
 * 분기를 하나라도 빠뜨리면 그대로 유출이 되기 때문이다.
 *
 * <h2>상세에는 내부 메모를 포함한다</h2>
 * 콘솔은 상담원용이므로 고객용과 달리 전체 답변을 내려준다
 * ({@code findByTicketIdOrderByCreatedAtAsc}). 상태 이력은 이 응답에 넣지 않고
 * {@code GET /api/console/tickets/{id}/histories} 가 따로 제공한다 — CS-02 우측의 독립
 * 패널이라 별도 조회가 캐싱·갱신에 유리하고(상태 변경 후 이력만 다시 받으면 된다), 상세 응답이
 * 불필요하게 커지지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConsoleTicketService {

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final TicketDetailAssembler assembler;

    /**
     * 티켓 목록 검색 (GET /api/console/tickets).
     *
     * <p>정렬은 {@code Pageable} 이 비어 있으면 기본 SLA 임박순
     * ({@code first_response_due_at ASC})이다 — 컨트롤러의 {@code @PageableDefault} 가 정한다.
     * 이 정렬은 {@code idx_ticket_sla(first_response_due_at) WHERE first_responded_at IS NULL}
     * 부분 인덱스를 탄다.
     *
     * <p>쿼리는 티켓 건수와 무관하게 3번이다 — 목록 1, 카운트 1, 이름 일괄 조회 1.
     *
     * @param actorRole AGENT 면 본인 담당분(또는 미배정)으로 강제된다. LEAD·ADMIN 은 전체를 본다
     */
    public PageResponse<TicketListItemResponse> search(TicketSearchCondition condition,
            ActorRole actorRole, Long actorId, Pageable pageable) {
        TicketSearchCondition effective = actorRole == ActorRole.AGENT
                ? condition.restrictedTo(actorId)
                : condition;

        Page<Ticket> page = ticketRepository.findAll(
                TicketSpecs.search(effective, slaPolicyRepository.findAll(), OffsetDateTime.now()),
                pageable);
        Map<Long, String> names = assembler.namesOf(page.getContent(), List.of());

        return PageResponse.from(page.map(t -> assembler.toListItem(t, names)));
    }

    /**
     * 티켓 상세 (GET /api/console/tickets/{id}). 답변에 내부 메모가 포함된다.
     *
     * <p>목록과 달리 담당자 여부를 보지 않는다 — 인수인계나 팀장 확인처럼 남의 티켓 상세를
     * 열어야 하는 경우가 정상 업무이고, docs/04 §7 도 이 경로를 AGENT+ 로 열어 두었다.
     * 상태 변경·답변 같은 <b>쓰기</b>는 각 API 가 담당자 검증을 한다.
     */
    public TicketDetailResponse findDetail(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
        List<TicketReply> replies = ticketReplyRepository
                .findByTicketIdOrderByCreatedAtAsc(ticketId);
        return assembler.toDetail(ticket, replies);
    }
}
