// @owner BSJ
package com.helpnest.domain.dashboard.agent;

import com.helpnest.domain.dashboard.agent.AgentDetail.Breakdown;
import com.helpnest.domain.dashboard.agent.AgentDetail.Daily;
import com.helpnest.domain.dashboard.agent.AgentDetail.RecentSurvey;
import com.helpnest.domain.dashboard.agent.AgentDetail.RecentTicket;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 상담원 상세 집계. {@code DashboardQueryRepository} 와 같은 규칙 — 남의 테이블은 SQL 로 읽기만 하고,
 * 기간은 호출자가 서울 기준으로 계산한 [from, to) 로 받는다.
 * 참조 컬럼: {@code ticket(ticket_id, ticket_no, title, status, priority, category, agent_id, sla_breached,
 * created_at, first_responded_at, resolved_at)}, {@code survey(ticket_id, rating, comment, submitted_at)}.
 * ponytail: 매 요청 실시간 집계 (일별은 최대 31행 × 상관 서브쿼리), 느려지면 집계 테이블
 */
@Repository
@RequiredArgsConstructor
public class AgentDetailQueryRepository {

    static final int RECENT_LIMIT = 20;

    private static final String MINE = "t.agent_id = :agentId AND t.created_at >= :from AND t.created_at < :to";

    private final JdbcClient jdbc;

    /** fromDay ~ toDay(서울 날짜, 양끝 포함) 하루씩. 티켓 없는 날도 0 으로 나온다 */
    public List<Daily> daily(Long agentId, OffsetDateTime from, OffsetDateTime to, LocalDate fromDay, LocalDate toDay) {
        return jdbc.sql("""
                        SELECT d.day,
                               (SELECT COUNT(*) FROM ticket t WHERE %1$s
                                  AND (t.created_at AT TIME ZONE 'Asia/Seoul')::date = d.day) AS received,
                               (SELECT COUNT(*) FROM ticket t WHERE t.agent_id = :agentId
                                  AND t.resolved_at >= :from AND t.resolved_at < :to
                                  AND (t.resolved_at AT TIME ZONE 'Asia/Seoul')::date = d.day) AS resolved,
                               (SELECT ROUND(AVG(EXTRACT(EPOCH FROM (t.first_responded_at - t.created_at)) / 60)::numeric, 1)
                                  FROM ticket t WHERE %1$s
                                  AND (t.created_at AT TIME ZONE 'Asia/Seoul')::date = d.day) AS avg_first_response_min
                        FROM (SELECT g::date AS day FROM generate_series(:fromDay::date, :toDay::date, interval '1 day') g) d
                        ORDER BY d.day
                        """.formatted(MINE))
                .param("agentId", agentId).param("from", from).param("to", to)
                .param("fromDay", fromDay).param("toDay", toDay)
                .query(Daily.class).list();
    }

    public List<Breakdown> byCategory(Long agentId, OffsetDateTime from, OffsetDateTime to) {
        return breakdown("category", agentId, from, to);
    }

    public List<Breakdown> byPriority(Long agentId, OffsetDateTime from, OffsetDateTime to) {
        return breakdown("priority", agentId, from, to);
    }

    // column 은 위 두 메서드의 상수만 들어온다 (외부 입력 없음)
    private List<Breakdown> breakdown(String column, Long agentId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT t.%1$s AS key, COUNT(*) AS count,
                               ROUND(AVG(EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 3600)::numeric, 2) AS avg_resolve_hour,
                               ROUND(100.0 * COUNT(*) FILTER (WHERE t.sla_breached) / COUNT(*), 1) AS sla_breach_rate
                        FROM ticket t WHERE %2$s
                        GROUP BY t.%1$s ORDER BY count DESC, key
                        """.formatted(column, MINE))
                .param("agentId", agentId).param("from", from).param("to", to)
                .query(Breakdown.class).list();
    }

    /** 기간 내 접수 담당 티켓 최근 순 */
    public List<RecentTicket> tickets(Long agentId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT t.ticket_id, t.ticket_no, t.title, t.status, t.priority, t.category, t.sla_breached, t.created_at
                        FROM ticket t WHERE %s
                        ORDER BY t.created_at DESC, t.ticket_id DESC LIMIT :limit
                        """.formatted(MINE))
                .param("agentId", agentId).param("from", from).param("to", to).param("limit", RECENT_LIMIT)
                .query(RecentTicket.class).list();
    }

    /** 기간 내 제출된 담당 티켓 설문 최근 순 (미응답 제외) */
    public List<RecentSurvey> surveys(Long agentId, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT t.ticket_id, t.ticket_no, s.rating, s.comment, s.submitted_at
                        FROM survey s JOIN ticket t ON t.ticket_id = s.ticket_id
                        WHERE t.agent_id = :agentId AND s.rating IS NOT NULL
                          AND s.submitted_at >= :from AND s.submitted_at < :to
                        ORDER BY s.submitted_at DESC, t.ticket_id DESC LIMIT :limit
                        """)
                .param("agentId", agentId).param("from", from).param("to", to).param("limit", RECENT_LIMIT)
                .query(RecentSurvey.class).list();
    }
}
