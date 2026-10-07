// @owner PMJ
package com.helpnest.domain.chat.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.chat.dto.ChatCloseRequest;
import com.helpnest.domain.chat.dto.ChatConvertResponse;
import com.helpnest.domain.chat.dto.ChatMessageResponse;
import com.helpnest.domain.chat.dto.ChatRoomResponse;
import com.helpnest.domain.chat.dto.ChatStatusPayload;
import com.helpnest.domain.chat.scheduler.ChatQueueScheduler;
import com.helpnest.domain.chat.service.ChatMessageService;
import com.helpnest.domain.chat.service.ChatService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 REST (docs/04 §10). 메시지 송신은 STOMP({@link ChatMessageController})다.
 *
 * <p>채팅은 회원 고객만 요청한다(PRD Q6) — 요청·전환·나가기는 {@code hasRole('CUSTOMER')} 하나로
 * 비회원 토큰과 직원이 함께 403 이 된다. 목록·메시지는 고객과 상담원이 같이 쓰고, 참여자 검증은
 * 서비스가 한다.
 */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatController {

    private static final String CUSTOMER_OR_STAFF = "hasAnyRole('CUSTOMER', 'AGENT')";

    private final ChatService chatService;
    private final ChatMessageService chatMessageService;
    private final ChatQueueScheduler chatQueue;

    /**
     * 채팅 요청. 방을 WAITING 으로 먼저 커밋한 뒤 연결을 1회 시도한다 — 연결 실패가 요청 실패가
     * 되지 않게 하려는 분리다({@link ChatService} 클래스 주석).
     */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<ChatStatusPayload>> request(@AuthenticationPrincipal Jwt jwt) {
        Long customerId = JwtProvider.memberId(jwt);
        Long roomId = chatService.openOrGetRoom(customerId);
        chatQueue.match(roomId);
        return ResponseEntity.ok(ApiResponse.ok(chatService.status(roomId, customerId)));
    }

    @GetMapping
    @PreAuthorize(CUSTOMER_OR_STAFF)
    public ResponseEntity<ApiResponse<List<ChatRoomResponse>>> rooms(@AuthenticationPrincipal Jwt jwt) {
        boolean customer = "CUSTOMER".equals(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM));
        return ResponseEntity.ok(ApiResponse.ok(chatMessageService.rooms(JwtProvider.memberId(jwt), customer)));
    }

    @GetMapping("/{roomId}/messages")
    @PreAuthorize(CUSTOMER_OR_STAFF)
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> messages(@PathVariable Long roomId,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                chatMessageService.messages(roomId, JwtProvider.memberId(jwt), before, size)));
    }

    @PostMapping("/{roomId}/convert")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<ChatConvertResponse>> convert(@PathVariable Long roomId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.convert(roomId, JwtProvider.memberId(jwt))));
    }

    @DeleteMapping("/{roomId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable Long roomId, @AuthenticationPrincipal Jwt jwt) {
        chatService.cancel(roomId, JwtProvider.memberId(jwt));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * 상담원의 채팅 종료. 상담원 자리가 하나 비었으므로 대기열을 바로 한 번 돌린다(docs/02 §6
     * "티켓 종료 시 즉시 재시도") — 다음 스케줄러 주기까지 기다리지 않는다.
     */
    @PatchMapping("/{roomId}/close")
    @PreAuthorize("hasRole('AGENT')")
    public ResponseEntity<ApiResponse<Void>> close(@PathVariable Long roomId,
            @RequestBody(required = false) ChatCloseRequest req, @AuthenticationPrincipal Jwt jwt) {
        chatService.close(roomId, JwtProvider.memberId(jwt), req != null && Boolean.TRUE.equals(req.resolve()));
        chatQueue.run();
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
