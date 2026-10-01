// @owner BSJ
package com.helpnest.domain.faq.controller;

import com.helpnest.domain.faq.dto.FaqRequest;
import com.helpnest.domain.faq.dto.FaqResponse;
import com.helpnest.domain.faq.service.FaqService;
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

/** FAQ 관리 (AD-02). 권한 LEAD+ 는 SecurityConfig 의 /api/admin/** 규칙 그대로 */
@RestController
@RequestMapping("/api/admin/faqs")
@RequiredArgsConstructor
public class AdminFaqController {

    private final FaqService faqService;

    /** 관리 표용 — 비공개 글 포함, 최신순 */
    @GetMapping
    public ApiResponse<PageResponse<FaqResponse>> list(
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(faqService.search(false, category, keyword, pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FaqResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FaqRequest req) {
        return ApiResponse.ok(faqService.create(JwtProvider.memberId(jwt), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<FaqResponse> update(@PathVariable Long id, @Valid @RequestBody FaqRequest req) {
        return ApiResponse.ok(faqService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        faqService.delete(id);
        return ApiResponse.ok();
    }
}
