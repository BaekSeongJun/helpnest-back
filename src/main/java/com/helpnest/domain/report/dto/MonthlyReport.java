// @owner SSJ
package com.helpnest.domain.report.dto;

import java.util.List;

/**
 * 월간 리포트 (docs/04 §13, FR-RPT-01). 대상은 그 달(Asia/Seoul)에 접수된 티켓.
 * 비율은 0~100, 대상이 없으면 비율·평균 null. avgRating 은 survey 테이블 전까지 항상 null.
 */
public record MonthlyReport(
        String month,
        long total,
        long prevTotal,
        Double avgFirstResponseMin,
        Double avgResolveHour,
        Double slaBreachRate,
        Double negativeRate,
        Double avgRating,
        List<CategoryRow> byCategory) {

    /** 유형별 행. 전월에만 있는 유형은 count=0, 지표 null */
    public record CategoryRow(String category, long count, long prevCount, Double avgResolveHour,
            Double negativeRate) {
    }
}
