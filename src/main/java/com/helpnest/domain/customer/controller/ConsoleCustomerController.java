// @owner BSJ
package com.helpnest.domain.customer.controller;

import com.helpnest.domain.customer.dto.CustomerHistoryResponse;
import com.helpnest.domain.customer.service.CustomerHistoryService;
import com.helpnest.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 고객 이력 묶음. 경로 전체가 SecurityConfig 의 {@code /api/console/**} = AGENT+ 규칙을 따른다 */
@RestController
@RequestMapping("/api/console/customers")
@RequiredArgsConstructor
public class ConsoleCustomerController {

    private final CustomerHistoryService historyService;

    /** CS-02 패널: 이 티켓 고객의 과거 문의(현재 티켓 제외) + 요약 */
    @GetMapping("/by-ticket/{ticketId}")
    public ApiResponse<CustomerHistoryResponse> byTicket(@PathVariable Long ticketId,
            @PageableDefault(size = 5) Pageable pageable) {
        return ApiResponse.ok(historyService.byTicket(ticketId, pageable));
    }

    /** CS-07: customerKey = M-{memberId} 또는 G-{email} */
    @GetMapping("/{customerKey}/tickets")
    public ApiResponse<CustomerHistoryResponse> byKey(@PathVariable String customerKey,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(historyService.byKey(customerKey, pageable));
    }
}
