// @owner BSJ
package com.helpnest.domain.member.controller;

import com.helpnest.domain.member.dto.AvailabilityRequest;
import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.dto.PasswordChangeRequest;
import com.helpnest.domain.member.dto.ProfileUpdateRequest;
import com.helpnest.domain.member.service.MemberService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** 내 정보 수정 (CU-10) — 이름·연락처 */
    @PatchMapping("/me")
    public ApiResponse<MemberResponse> updateMe(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProfileUpdateRequest req) {
        return ApiResponse.ok(memberService.updateProfile(JwtProvider.memberId(jwt), req));
    }

    /** 비밀번호 변경 (CU-10). 성공 시 Refresh 전부 폐기 → 프론트는 로그아웃 처리 */
    @PatchMapping("/me/password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PasswordChangeRequest req) {
        memberService.changePassword(JwtProvider.memberId(jwt), req);
        return ApiResponse.ok();
    }

    @PatchMapping("/me/availability")
    public ApiResponse<MemberResponse> updateAvailability(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AvailabilityRequest req) {
        return ApiResponse.ok(memberService.updateAvailability(JwtProvider.memberId(jwt), req.available()));
    }
}
