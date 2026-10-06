// @owner PMJ
package com.helpnest.domain.sla.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.sla.dto.SlaPolicyResponse;
import com.helpnest.domain.sla.dto.SlaPolicyUpdateRequest;
import com.helpnest.domain.sla.service.SlaPolicyService;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.global.common.ApiResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * SLA 정책 관리 API (docs/04 §8, 화면 AD-02).
 *
 * <p>조회는 LEAD+ 다 — {@code /api/admin/**} 에 걸린 URL 규칙이 그대로 맞으므로 메서드 보안을
 * 따로 걸지 않는다. <b>수정만 ADMIN 으로 좁힌다</b>({@code AdminMemberController} 와 같은 방식).
 * 기한을 바꾸는 일은 전체 상담원의 평가 기준을 바꾸는 것이라 팀장 권한으로는 부족하다.
 */
@RestController
@RequestMapping("/api/admin/sla-policies")
@RequiredArgsConstructor
public class AdminSlaPolicyController {

    private final SlaPolicyService slaPolicyService;

    @GetMapping
    public ApiResponse<List<SlaPolicyResponse>> list() {
        return ApiResponse.ok(slaPolicyService.findAll());
    }

    @PutMapping("/{priority}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<SlaPolicyResponse> update(@PathVariable TicketPriority priority,
            @Valid @RequestBody SlaPolicyUpdateRequest req) {
        return ApiResponse.ok(slaPolicyService.update(priority, req));
    }
}
