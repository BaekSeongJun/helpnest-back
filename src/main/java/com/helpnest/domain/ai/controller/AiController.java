// @owner SSJ
package com.helpnest.domain.ai.controller;

import com.helpnest.domain.ai.dto.AiResultResponse;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ai.service.ClassifyService;
import com.helpnest.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI 분류 API (docs/04 §12). 권한 AGENT+ 는 SecurityConfig 의 /api/console/** 규칙 */
@RestController
@RequestMapping("/api/console/tickets/{ticketId}/ai")
@RequiredArgsConstructor
public class AiController {

    private final TicketAiResultRepository repository;
    private final ClassifyService classifyService;

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
}
