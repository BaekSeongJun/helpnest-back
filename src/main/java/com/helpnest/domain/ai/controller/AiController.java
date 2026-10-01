// @owner SSJ
package com.helpnest.domain.ai.controller;

import com.helpnest.domain.ai.dto.AiResultResponse;
import com.helpnest.domain.ai.dto.DraftResponse;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ai.service.ClassifyService;
import com.helpnest.domain.ai.service.DraftService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 분류·초안 API (docs/04 §12). 권한 AGENT+ 는 SecurityConfig 의 /api/console/** 규칙 */
@RestController
@RequestMapping("/api/console/tickets/{ticketId}/ai")
@RequiredArgsConstructor
public class AiController {

    private final TicketAiResultRepository repository;
    private final ClassifyService classifyService;
    private final DraftService draftService;

    /** 결과가 아직 없으면(비동기 분류 진행 중) data=null */
    @GetMapping
    public ApiResponse<AiResultResponse> get(@PathVariable Long ticketId) {
        return ApiResponse.ok(repository.findByTicketId(ticketId).map(AiResultResponse::from).orElse(null));
    }

    /** 동기 재분류. LLM 실패여도 status=FAILED 로 200 */
    @PostMapping("/classify")
    public ApiResponse<AiResultResponse> classify(@PathVariable Long ticketId) {
        return ApiResponse.ok(AiResultResponse.from(classifyService.classify(ticketId)));
    }

    /** 초안 생성. AGENT 는 본인 담당 티켓만(403 AI_NOT_ASSIGNEE), LLM 실패는 503 AI_PROVIDER_UNAVAILABLE */
    @PostMapping("/drafts")
    public ApiResponse<DraftResponse> createDraft(@PathVariable Long ticketId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(draftService.generate(ticketId, JwtProvider.memberId(jwt), !isLeadOrAbove(jwt)));
    }

    /** 초안 목록 (최신순) */
    @GetMapping("/drafts")
    public ApiResponse<List<DraftResponse>> listDrafts(@PathVariable Long ticketId) {
        return ApiResponse.ok(draftService.list(ticketId));
    }

    // 역할 계층은 Security 가 쓰고, 서비스 분기용으로는 클레임을 직접 본다
    private static boolean isLeadOrAbove(Jwt jwt) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        return "LEAD".equals(role) || "ADMIN".equals(role);
    }
}
