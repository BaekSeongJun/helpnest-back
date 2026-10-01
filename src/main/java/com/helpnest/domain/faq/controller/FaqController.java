// @owner BSJ
package com.helpnest.domain.faq.controller;

import com.helpnest.domain.faq.dto.FaqResponse;
import com.helpnest.domain.faq.service.FaqService;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 고객용 FAQ (CU-02). 공개 — 공개 글만 */
@RestController
@RequestMapping("/api/faqs")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService;

    @GetMapping
    public ApiResponse<PageResponse<FaqResponse>> list(
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "viewCount", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(faqService.search(true, category, keyword, pageable));
    }

    /** 아코디언을 열 때 호출 → 조회수 +1 */
    @GetMapping("/{id}")
    public ApiResponse<FaqResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(faqService.getPublished(id));
    }
}
