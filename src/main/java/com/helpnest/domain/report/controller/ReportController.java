// @owner SSJ
package com.helpnest.domain.report.controller;

import com.helpnest.domain.report.dto.MonthlyReport;
import com.helpnest.domain.report.service.ReportService;
import com.helpnest.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 월간 리포트 (docs/04 §13). LEAD+ 는 SecurityConfig 가 /api/reports/** 에서 막는다 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/monthly")
    public ApiResponse<MonthlyReport> monthly(@RequestParam(required = false) String month) {
        return ApiResponse.ok(reportService.monthly(month));
    }
}
