// @owner PMJ
package com.helpnest.domain.chat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.chat.dto.ChatConvertResponse;
import com.helpnest.domain.chat.dto.ChatStatusPayload;
import com.helpnest.domain.chat.scheduler.ChatQueueScheduler;
import com.helpnest.domain.chat.service.ChatService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;

import lombok.RequiredArgsConstructor;

/**
 * 고객 채팅 요청·전환·나가기 (docs/04 §10). 채팅은 회원 고객만 쓴다(PRD Q6) —
 * {@code hasRole('CUSTOMER')} 하나로 비회원 토큰과 직원(AGENT+)이 함께 403 이 된다.
 */
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class ChatController {

    private final ChatService chatService;
    private final ChatQueueScheduler chatQueue;

    /**
     * 채팅 요청. 방을 WAITING 으로 먼저 커밋한 뒤 연결을 1회 시도한다 — 연결 실패가 요청 실패가
     * 되지 않게 하려는 분리다({@link ChatService} 클래스 주석).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ChatStatusPayload>> request(@AuthenticationPrincipal Jwt jwt) {
        Long customerId = JwtProvider.memberId(jwt);
        Long roomId = chatService.openOrGetRoom(customerId);
        chatQueue.match(roomId);
        return ResponseEntity.ok(ApiResponse.ok(chatService.status(roomId, customerId)));
    }

    @PostMapping("/{roomId}/convert")
    public ResponseEntity<ApiResponse<ChatConvertResponse>> convert(@PathVariable Long roomId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.convert(roomId, JwtProvider.memberId(jwt))));
    }

    @DeleteMapping("/{roomId}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable Long roomId, @AuthenticationPrincipal Jwt jwt) {
        chatService.cancel(roomId, JwtProvider.memberId(jwt));
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
