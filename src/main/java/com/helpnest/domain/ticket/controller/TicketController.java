// @owner PMJ
package com.helpnest.domain.ticket.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.ticket.dto.CustomerReplyCreateRequest;
import com.helpnest.domain.ticket.dto.ReplyResponse;
import com.helpnest.domain.ticket.dto.TicketCreateRequest;
import com.helpnest.domain.ticket.dto.TicketCreateResponse;
import com.helpnest.domain.ticket.dto.TicketDetailResponse;
import com.helpnest.domain.ticket.dto.TicketListItemResponse;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.service.CustomerTicketService;
import com.helpnest.domain.ticket.service.TicketReplyService;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 고객용 티켓 API (docs/04 §7). 콘솔용은 ConsoleTicketController 가 맡는다. */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final CustomerTicketService customerTicketService;
    private final TicketReplyService ticketReplyService;

    /**
     * 문의 접수 (CU-03). SecurityConfig 가 permitAll 로 열어 둔 경로라 비로그인 요청이
     * 들어올 수 있으므로 {@code jwt} 가 null 일 수 있다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TicketCreateResponse>> create(
            @Valid @RequestBody TicketCreateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        TicketCreateResponse created = ticketService.create(req, memberIdOrNull(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * 내 문의 목록 (CU-08). 회원 전용이다 — Guest 토큰은
     * {@code JwtProvider.memberId} 가 403 으로 막는다. 비회원은 티켓 1건에만 유효한 토큰을
     * 받으므로 "목록"이 성립하지 않고, 상세 조회로 그 1건을 본다.
     */
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<PageResponse<TicketListItemResponse>>> myTickets(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                customerTicketService.findMyTickets(JwtProvider.memberId(jwt), pageable)));
    }

    /**
     * 문의 상세 (CU-07). 회원 본인 또는 그 티켓의 Guest 토큰만 볼 수 있고,
     * 응답의 답변 목록에는 내부 메모가 포함되지 않는다.
     *
     * <p>AGENT+ 는 이 경로를 쓰지 않는다 — 상담원은 내부 메모와 이력이 함께 필요하므로
     * 콘솔 상세 API({@code GET /api/console/tickets/{id}})가 그 역할을 맡는다. 여기서 상담원까지
     * 받으면 "내부 메모가 빠진 상세"를 상담원이 보게 되어 오히려 혼선이 생긴다.
     */
    @GetMapping("/{ticketId}")
    public ResponseEntity<ApiResponse<TicketDetailResponse>> detail(
            @PathVariable Long ticketId,
            @AuthenticationPrincipal Jwt jwt) {
        Long guestTicketId = JwtProvider.guestTicketId(jwt);
        TicketDetailResponse detail = guestTicketId != null
                ? customerTicketService.findGuestTicket(requireSameTicket(guestTicketId, ticketId))
                : customerTicketService.findMyTicket(ticketId, JwtProvider.memberId(jwt));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    /**
     * 고객 추가 답글 (FR-INQ-06). RESOLVED 였다면 재문의로 IN_PROGRESS 가 되고,
     * CLOSED 면 {@code TICKET_ALREADY_CLOSED}(409) 다.
     */
    @PostMapping("/{ticketId}/replies")
    public ResponseEntity<ApiResponse<ReplyResponse>> addReply(
            @PathVariable Long ticketId,
            @Valid @RequestBody CustomerReplyCreateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        Long guestTicketId = JwtProvider.guestTicketId(jwt);
        if (guestTicketId != null) {
            requireSameTicket(guestTicketId, ticketId);
        }
        ReplyResponse created = ticketReplyService.addCustomerReply(ticketId, req,
                guestTicketId != null ? null : JwtProvider.memberId(jwt));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    /**
     * Guest 토큰은 발급 대상 티켓 1건에만 유효하다(CR #34 에서 백성준과 합의한 규약).
     *
     * <p>불일치를 403 이 아니라 404 로 돌려주는 이유는 비소유 티켓 처리와 같다 — 403 은
     * "그 티켓은 있지만 네 토큰으로는 못 본다"가 되어 티켓 id 를 순회하며 실재하는 번호를
     * 확인하는 수단이 된다.
     */
    private static Long requireSameTicket(Long guestTicketId, Long requestedTicketId) {
        if (!guestTicketId.equals(requestedTicketId)) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        return requestedTicketId;
    }

    /**
     * 로그인 회원의 member_id. 토큰이 없거나 <b>Guest 토큰이면 null</b> 이다.
     *
     * <p>Guest 토큰(비회원 조회용)은 sub 에 회원 id 가 들어 있지 않으므로 그대로
     * {@code JwtProvider.memberId} 에 넘기면 엉뚱한 회원의 티켓으로 저장된다. 백성준의
     * {@code AttachmentService.memberIdOrNull} 과 같은 방식으로 role 클레임을 먼저 본다.
     */
    private static Long memberIdOrNull(Jwt jwt) {
        if (jwt == null || JwtProvider.GUEST_ROLE.equals(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM))) {
            return null;
        }
        return JwtProvider.memberId(jwt);
    }
}
