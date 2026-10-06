// @owner BSJ
package com.helpnest.domain.survey.controller;

import com.helpnest.domain.survey.dto.SurveyResultResponse;
import com.helpnest.domain.survey.dto.SurveySummaryResponse;
import com.helpnest.domain.survey.service.SurveyResultService;
import com.helpnest.domain.survey.service.SurveyResultService.Filter;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.security.JwtProvider;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 설문 결과 (CS-06). 경로 전체가 SecurityConfig 의 {@code /api/console/**} = AGENT+ 규칙을 따른다.
 * AGENT 는 요청의 agentId 를 무시하고 항상 본인 담당분만 본다(서버 강제) — 파라미터를 바꿔 남의 결과를
 * 보는 길을 만들지 않기 위해 403 이 아니라 덮어쓴다. LEAD+ 는 전체를 보고 agentId 로 좁힐 수 있다.
 */
@RestController
@RequestMapping("/api/console/surveys")
@RequiredArgsConstructor
public class ConsoleSurveyController {

    private final SurveyResultService resultService;

    @GetMapping
    public ApiResponse<PageResponse<SurveyResultResponse>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) TicketCategory category,
            @PageableDefault(size = 20) Pageable pageable,
            @AuthenticationPrincipal Jwt jwt) {
        Filter filter = new Filter(from, to, rating, scopedAgentId(jwt, agentId), category);
        return ApiResponse.ok(resultService.list(filter, pageable));
    }

    @GetMapping("/summary")
    public ApiResponse<SurveySummaryResponse> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer rating,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) TicketCategory category,
            @AuthenticationPrincipal Jwt jwt) {
        Filter filter = new Filter(from, to, rating, scopedAgentId(jwt, agentId), category);
        return ApiResponse.ok(resultService.summary(filter));
    }

    /** AGENT 는 본인, LEAD+ 는 요청값(없으면 전체) */
    private static Long scopedAgentId(Jwt jwt, Long requested) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        boolean leadOrAbove = "LEAD".equals(role) || "ADMIN".equals(role);
        return leadOrAbove ? requested : JwtProvider.memberId(jwt);
    }
}
