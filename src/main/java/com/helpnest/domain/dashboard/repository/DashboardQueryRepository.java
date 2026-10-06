// @owner SSJ
package com.helpnest.domain.dashboard.repository;

import com.helpnest.domain.dashboard.dto.AgentStat;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 대시보드 집계 (docs/03 §4.4). docs/02 §5 읽기 전용 예외: 남의 테이블을 SQL 로 읽기만 하고 엔티티는 import 하지 않는다.
 * 참조 컬럼(PR 본문에도 기재): {@code ticket(ticket_id, status, agent_id, category, sla_breached, created_at,
 * first_responded_at, resolved_at)}, {@code member(member_id, name, role, status)}.
 *
 * <p>기간은 [from, to) 로 받는다 — 시각 계산은 호출자(Asia/Seoul)가 하고 DB 세션 타임존·NOW() 에 의존하지 않는다.
 * ponytail: avg_rating 은 NULL 고정, 백성준 survey 테이블 머지 후 LEFT JOIN survey 로 교체
 * ponytail: 매 요청 실시간 집계, 데이터가 커지면 캐시 또는 집계 테이블
 */
@Repository
@RequiredArgsConstructor
public class DashboardQueryRepository {

    private static final String PERIOD = "created_at >= :from AND created_at < :to";

    // 03 §4.4 기반. 상담원 기준 LEFT JOIN 이라 티켓 0건 상담원도 행이 나온다
    private static final String AGENT_SQL = """
            SELECT m.member_id AS agent_id, m.name,
                   (SELECT COUNT(*) FROM ticket x WHERE x.agent_id = m.member_id AND x.status = 'ASSIGNED')    AS assigned_count,
                   (SELECT COUNT(*) FROM ticket x WHERE x.agent_id = m.member_id AND x.status = 'IN_PROGRESS') AS in_progress_count,
                   (SELECT COUNT(*) FROM ticket x WHERE x.agent_id = m.member_id
                                                    AND x.resolved_at >= :todayStart AND x.resolved_at < :to) AS resolved_today,
                   ROUND(AVG(EXTRACT(EPOCH FROM (t.first_responded_at - t.created_at)) / 60)::numeric, 1)    AS avg_first_response_min,
                   ROUND(AVG(EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 3600)::numeric, 2)         AS avg_resolve_hour,
                   ROUND(100.0 * COUNT(t.ticket_id) FILTER (WHERE t.sla_breached)
                         / NULLIF(COUNT(t.ticket_id), 0), 1)                                                  AS sla_breach_rate,
                   NULL::numeric                                                                              AS avg_rating
            FROM member m
            LEFT JOIN ticket t ON t.agent_id = m.member_id AND t.created_at >= :from AND t.created_at < :to
            WHERE %s
            GROUP BY m.member_id, m.name
            ORDER BY m.name, m.member_id
            """;

    private final JdbcClient jdbc;

    public record Kpi(long total, Double slaBreachRate, Double avgFirstResponseMin) {
    }

    public Kpi kpi(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS total,
                               ROUND(100.0 * COUNT(*) FILTER (WHERE sla_breached) / NULLIF(COUNT(*), 0), 1) AS sla_breach_rate,
                               ROUND(AVG(EXTRACT(EPOCH FROM (first_responded_at - created_at)) / 60)::numeric, 1)
                                   AS avg_first_response_min
                        FROM ticket
                        WHERE\s""" + PERIOD)
                .param("from", from).param("to", to)
                .query(Kpi.class).single();
    }

    /** 현재 미배정이면서 종료되지 않은 티켓 (기간 무관) */
    public long unassigned() {
        return jdbc.sql("SELECT COUNT(*) FROM ticket WHERE agent_id IS NULL AND status NOT IN ('RESOLVED', 'CLOSED')")
                .query(Long.class).single();
    }

    /** 기간 내 접수 티켓의 상태별 건수 (많은 순) */
    public Map<String, Long> countByStatus(OffsetDateTime from, OffsetDateTime to) {
        return countBy("status", from, to);
    }

    /** 기간 내 접수 티켓의 유형별 건수 (많은 순) */
    public Map<String, Long> countByCategory(OffsetDateTime from, OffsetDateTime to) {
        return countBy("category", from, to);
    }

    // column 은 위 두 메서드의 상수만 들어온다 (SQL 조립이지만 외부 입력 없음)
    private Map<String, Long> countBy(String column, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.sql("SELECT " + column + " AS k, COUNT(*) AS c FROM ticket WHERE " + PERIOD
                        + " GROUP BY " + column + " ORDER BY c DESC, k")
                .param("from", from).param("to", to)
                .query(rs -> {
                    result.put(rs.getString("k"), rs.getLong("c"));
                });
        return result;
    }

    /** 활성 AGENT 전원 (티켓 0건이어도 포함) */
    public List<AgentStat> agents(OffsetDateTime from, OffsetDateTime to, OffsetDateTime todayStart) {
        return jdbc.sql(AGENT_SQL.formatted("m.role = 'AGENT' AND m.status = 'ACTIVE'"))
                .param("from", from).param("to", to).param("todayStart", todayStart)
                .query(AgentStat.class).list();
    }

    /** 본인 1행 (역할 무관) */
    public AgentStat agent(Long memberId, OffsetDateTime from, OffsetDateTime to, OffsetDateTime todayStart) {
        return jdbc.sql(AGENT_SQL.formatted("m.member_id = :memberId"))
                .param("memberId", memberId).param("from", from).param("to", to).param("todayStart", todayStart)
                .query(AgentStat.class).single();
    }
}
