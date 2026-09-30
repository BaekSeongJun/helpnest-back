// @owner BSJ
package com.helpnest.domain.member.controller;

import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.service.MemberService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/me")
    public ApiResponse<MemberResponse> getMe(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(memberService.getMe(JwtProvider.memberId(jwt)));
    }
}
