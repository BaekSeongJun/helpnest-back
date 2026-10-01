// @owner BSJ
package com.helpnest.domain.template.controller;

import com.helpnest.domain.template.dto.TemplateRequest;
import com.helpnest.domain.template.dto.TemplateResponse;
import com.helpnest.domain.template.service.TemplateService;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.security.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 템플릿 관리 (AD-03). 권한 LEAD+ 는 SecurityConfig 의 /api/admin/** 규칙 그대로 */
@RestController
@RequestMapping("/api/admin/templates")
@RequiredArgsConstructor
public class AdminTemplateController {

    private final TemplateService templateService;

    /** 관리 표용 — 미사용 포함, 최신순 */
    @GetMapping
    public ApiResponse<PageResponse<TemplateResponse>> list(
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(templateService.search(false, category, keyword, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TemplateResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody TemplateRequest req) {
        return ApiResponse.ok(templateService.create(JwtProvider.memberId(jwt), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<TemplateResponse> update(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        return ApiResponse.ok(templateService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return ApiResponse.ok();
    }
}
