// @owner SSJ
package com.helpnest.domain.report.controller;

import com.helpnest.domain.report.csv.Csv;
import com.helpnest.domain.report.dto.MonthlyReport;
import com.helpnest.domain.report.dto.MonthlyReport.CategoryRow;
import com.helpnest.domain.report.service.ReportService;
import com.helpnest.global.common.ApiResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 월간 리포트 (docs/04 §13). LEAD+ 는 SecurityConfig 가 /api/reports/** 에서 막는다 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    static final List<String> REPORT_HEADER =
            List.of("유형", "건수", "전월 건수", "증감률(%)", "평균 처리시간(시간)", "불만 비율(%)");

    private final ReportService reportService;

    @GetMapping("/monthly")
    public ApiResponse<MonthlyReport> monthly(@RequestParam(required = false) String month) {
        return ApiResponse.ok(reportService.monthly(month));
    }

    /** 유형별 행 + 합계 행 (FR-RPT-02) */
    @GetMapping("/monthly/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String month) {
        MonthlyReport r = reportService.monthly(month);
        List<List<?>> rows = new ArrayList<>();
        for (CategoryRow c : r.byCategory()) {
            rows.add(Arrays.asList(Csv.categoryLabel(c.category()), c.count(), c.prevCount(),
                    changeRate(c.count(), c.prevCount()), c.avgResolveHour(), c.negativeRate()));
        }
        rows.add(Arrays.asList("합계", r.total(), r.prevTotal(), changeRate(r.total(), r.prevTotal()),
                r.avgResolveHour(), r.negativeRate()));
        return Csv.download("helpnest_report_" + r.month() + ".csv", Csv.write(REPORT_HEADER, rows));
    }

    /** 전월 대비 증감률(소수 1자리). 전월 0 이면 null */
    static Double changeRate(long count, long prevCount) {
        return prevCount == 0 ? null : Math.round(1000.0 * (count - prevCount) / prevCount) / 10.0;
    }
}
