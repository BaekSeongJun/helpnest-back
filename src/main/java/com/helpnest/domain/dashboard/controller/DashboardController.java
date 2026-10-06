// @owner SSJ
package com.helpnest.domain.dashboard.controller;

import com.helpnest.domain.dashboard.dto.AgentStat;
import com.helpnest.domain.dashboard.dto.DashboardSummary;
import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.domain.dashboard.service.DashboardService;
import com.helpnest.domain.report.csv.Csv;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 API (docs/04 §13). SecurityConfig 가 /api/dashboard/** 를 AGENT+ 로 막고,
 * 팀 전체(summary·agents)는 LEAD+ 만 — AGENT 는 본인 지표(me)만 (FR-DSH-03).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    static final List<String> AGENT_HEADER = List.of("상담원", "배정", "처리중", "오늘 해결", "평균 첫 응답(분)",
            "평균 해결(시간)", "SLA 위반율(%)", "평균 만족도");

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary(@RequestParam(required = false) String period,
            @AuthenticationPrincipal Jwt jwt) {
        requireLead(jwt);
        return ApiResponse.ok(dashboardService.summary(Period.parse(period)));
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentStat>> agents(@RequestParam(required = false) String period,
            @AuthenticationPrincipal Jwt jwt) {
        requireLead(jwt);
        return ApiResponse.ok(dashboardService.agents(Period.parse(period)));
    }

    /** 상담원별 처리현황 CSV (FR-RPT-02). 파일명 helpnest_agents_{period}_{서울 yyyyMMdd}.csv */
    @GetMapping("/agents/export")
    public ResponseEntity<byte[]> exportAgents(@RequestParam(required = false) String period,
            @AuthenticationPrincipal Jwt jwt) {
        requireLead(jwt);
        List<List<?>> rows = dashboardService.agents(Period.parse(period)).stream()
                .<List<?>>map(a -> Arrays.asList(a.name(), a.assignedCount(), a.inProgressCount(), a.resolvedToday(),
                        a.avgFirstResponseMin(), a.avgResolveHour(), a.slaBreachRate(), a.avgRating()))
                .toList();
        String filename = "helpnest_agents_%s_%s.csv".formatted(period == null ? "TODAY" : period,
                LocalDate.now(Period.SEOUL).format(DateTimeFormatter.BASIC_ISO_DATE));
        return Csv.download(filename, Csv.write(AGENT_HEADER, rows));
    }

    @GetMapping("/agents/me")
    public ApiResponse<AgentStat> me(@RequestParam(required = false) String period, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(dashboardService.me(JwtProvider.memberId(jwt), Period.parse(period)));
    }

    // 경로 규칙은 AGENT+ 뿐이라 LEAD+ 구분은 클레임을 직접 본다 (전역 핸들러가 403 응답)
    private static void requireLead(Jwt jwt) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        if (!"LEAD".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("LEAD 이상만 조회할 수 있습니다.");
        }
    }
}
