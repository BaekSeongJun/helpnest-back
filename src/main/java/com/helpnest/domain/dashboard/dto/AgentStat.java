// @owner SSJ
package com.helpnest.domain.dashboard.dto;

/**
 * 상담원별 처리현황 (FR-DSH-01). assigned·inProgress 는 현재 담당 건수, resolvedToday 는 오늘(서울) 해결,
 * 평균·비율은 기간 내 접수 티켓 기준(대상 없으면 null). avgRating 은 응답된 설문 평균(소수 1자리, 응답 없으면 null).
 */
public record AgentStat(
        Long agentId,
        String name,
        long assignedCount,
        long inProgressCount,
        long resolvedToday,
        Double avgFirstResponseMin,
        Double avgResolveHour,
        Double slaBreachRate,
        Double avgRating) {
}
