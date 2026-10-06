// @owner BSJ
package com.helpnest.domain.template.controller;

import com.helpnest.domain.template.dto.TemplateResponse;
import com.helpnest.domain.template.service.TemplateService;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.common.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상담원 TemplatePicker 용 (FR-TPL-02). 권한 AGENT+ 는 SecurityConfig 의 /api/templates/** 규칙 그대로 */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /** 사용 중인 템플릿만, 제목순 */
    @GetMapping
    public ApiResponse<PageResponse<TemplateResponse>> list(
            @RequestParam(required = false) TicketCategory category,
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 50, sort = "title") Pageable pageable) {
        return ApiResponse.ok(templateService.search(true, category, keyword, pageable));
    }
}
