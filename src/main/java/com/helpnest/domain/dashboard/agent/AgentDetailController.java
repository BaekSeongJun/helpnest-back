// @owner BSJ
package com.helpnest.domain.dashboard.agent;

import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상담원 개인 상세 (docs/04 §13.x). 경로 규칙은 /api/dashboard/** AGENT+ 라 LEAD+ 는 클레임으로 본다 (back #112) */
@RestController
@RequiredArgsConstructor
public class AgentDetailController {

    private final AgentDetailService agentDetailService;

    @GetMapping("/api/dashboard/agents/{agentId}/detail")
    public ApiResponse<AgentDetail> detail(@PathVariable Long agentId, @RequestParam(required = false) String period,
            @AuthenticationPrincipal Jwt jwt) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        if (!"LEAD".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("LEAD 이상만 조회할 수 있습니다.");
        }
        return ApiResponse.ok(agentDetailService.detail(agentId, Period.parse(period)));
    }
}
