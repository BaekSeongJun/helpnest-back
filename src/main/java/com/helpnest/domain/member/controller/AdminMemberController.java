// @owner BSJ
package com.helpnest.domain.member.controller;

import com.helpnest.domain.member.dto.AdminMemberCreateRequest;
import com.helpnest.domain.member.dto.AdminMemberResponse;
import com.helpnest.domain.member.dto.AdminMemberUpdateRequest;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.service.AdminMemberService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.security.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** URL 규칙은 /api/admin/** = LEAD+ 라서, 계정 관리는 여기서 ADMIN 으로 좁힌다 (SecurityConfig 주석) */
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping
    public ApiResponse<PageResponse<AdminMemberResponse>> list(
            @RequestParam(required = false) MemberRole role,
            @RequestParam(required = false) MemberStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(adminMemberService.list(role, status, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminMemberResponse> create(@Valid @RequestBody AdminMemberCreateRequest req) {
        return ApiResponse.ok(adminMemberService.create(req));
    }

    @PatchMapping("/{id}")
    public ApiResponse<AdminMemberResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestBody AdminMemberUpdateRequest req) {
        return ApiResponse.ok(adminMemberService.update(JwtProvider.memberId(jwt), id, req));
    }
}
