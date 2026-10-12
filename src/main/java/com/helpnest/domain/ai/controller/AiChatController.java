// @owner SSJ
package com.helpnest.domain.ai.controller;

import com.helpnest.domain.ai.dto.AiChatRequest;
import com.helpnest.domain.ai.dto.AiChatResponse;
import com.helpnest.domain.ai.service.AiChatService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** AI 상담 (docs/04 §12). 회원 고객 전용 — 비회원 토큰·직원은 403 (채팅과 같은 규칙) */
@RestController
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/api/ai/chat")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<AiChatResponse> chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AiChatRequest req) {
        return ApiResponse.ok(aiChatService.chat(JwtProvider.memberId(jwt), req.question(), req.history()));
    }
}
