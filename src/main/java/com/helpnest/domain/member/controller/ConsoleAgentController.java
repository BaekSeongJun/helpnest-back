// @owner BSJ
package com.helpnest.domain.member.controller;

import com.helpnest.domain.member.dto.ConsoleAgentResponse;
import com.helpnest.domain.member.service.MemberService;
import com.helpnest.global.common.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘솔 배정 드롭다운용 상담원 목록 (CR #44, FR-ASN-02). 권한 AGENT+ 는 SecurityConfig 의 /api/console/** 규칙 그대로 —
 * 배정 API 자체는 LEAD+ 지만 목록은 이름·가용 여부뿐이라 상담원에게 보여도 된다
 */
@RestController
@RequestMapping("/api/console/agents")
@RequiredArgsConstructor
public class ConsoleAgentController {

    private final MemberService memberService;

    /** 활성 상담원 전부(상담 불가 포함 — 재배정은 가용 여부와 무관), 이름순 */
    @GetMapping
    public ApiResponse<List<ConsoleAgentResponse>> list() {
        return ApiResponse.ok(memberService.findConsoleAgents());
    }
}
