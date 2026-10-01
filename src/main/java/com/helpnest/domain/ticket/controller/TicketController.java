// @owner PMJ
package com.helpnest.domain.ticket.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.ticket.dto.TicketCreateRequest;
import com.helpnest.domain.ticket.dto.TicketCreateResponse;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 고객용 티켓 API (docs/04 §7). 콘솔용은 ConsoleTicketController 가 맡는다. */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

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
