// @owner SSJ
package com.helpnest.domain.dashboard.dto;

import java.util.Map;

/**
 * 팀 전체 KPI + 분포 (docs/04 §13, FR-DSH-02). unassigned 만 현재 상태, 나머지는 기간 내 접수 티켓 기준.
 * 비율·평균은 대상이 없으면 null. avgRating 은 응답된 설문 평균(소수 1자리, 응답 없으면 null).
 */
public record DashboardSummary(
        long total,
        long unassigned,
        Double slaBreachRate,
        Double avgFirstResponseMin,
        Double avgRating,
        Map<String, Long> byStatus,
        Map<String, Long> byCategory) {
}
