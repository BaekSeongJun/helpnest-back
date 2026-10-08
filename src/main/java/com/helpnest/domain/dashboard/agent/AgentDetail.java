// @owner BSJ
package com.helpnest.domain.dashboard.agent;

import com.helpnest.domain.dashboard.dto.AgentStat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 상담원 개인 상세 (docs/04 §13.x, LEAD+). agent 는 {@code /api/dashboard/agents} 와 같은 1행,
 * teamAverage 는 그 목록(활성 AGENT 전원)의 상담원 평균.
 */
public record AgentDetail(
        AgentStat agent,
        TeamAverage teamAverage,
        List<Daily> daily,
        List<Breakdown> byCategory,
        List<Breakdown> byPriority,
        List<RecentTicket> tickets,
        List<RecentSurvey> surveys) {

    /** 건수는 0건 상담원 포함 평균, 시간·비율·만족도는 값이 있는 상담원만 평균(없으면 null) */
    public record TeamAverage(
            int agentCount,
            double assignedCount,
            double inProgressCount,
            double resolvedToday,
            Double avgFirstResponseMin,
            Double avgResolveHour,
            Double slaBreachRate,
            Double avgRating) {
    }

    /** 서울 날짜별: 그날 접수된 담당 티켓 수, 그날 해결 수, 그날 접수분 평균 첫 응답(분) */
    public record Daily(LocalDate day, long received, long resolved, Double avgFirstResponseMin) {
    }

    /** 기간 내 접수 담당 티켓을 유형(category) 또는 우선순위(priority)로 묶은 것 */
    public record Breakdown(String key, long count, Double avgResolveHour, Double slaBreachRate) {
    }

    public record RecentTicket(Long ticketId, String ticketNo, String title, String status, String priority,
            String category, boolean slaBreached, OffsetDateTime createdAt) {
    }

    public record RecentSurvey(Long ticketId, String ticketNo, int rating, String comment,
            OffsetDateTime submittedAt) {
    }
}
