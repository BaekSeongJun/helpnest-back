// @owner SSJ
package com.helpnest.domain.report.repository;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 월간 리포트 집계 (docs/03 §4.5). docs/02 §5 읽기 전용 예외: 남의 테이블을 SQL 로 읽기만 한다.
 * 참조 컬럼(PR 본문에도 기재): {@code ticket(category, sentiment, sla_breached, created_at, first_responded_at,
 * resolved_at)}, {@code survey(ticket_id, rating)}. 기간 [from, to) 는 호출자가 Asia/Seoul 로 계산한다.
 * 만족도는 응답된 설문만 평균(survey.ticket_id UNIQUE 라 LEFT JOIN 해도 건수가 늘지 않음).
 */
@Repository
@RequiredArgsConstructor
public class ReportQueryRepository {

    private static final String PERIOD = "created_at >= :from AND created_at < :to";
    private static final String AVG_RESOLVE_HOUR =
            "ROUND(AVG(EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600)::numeric, 2)";
    private static final String NEGATIVE_RATE =
            "ROUND(100.0 * COUNT(*) FILTER (WHERE sentiment = 'NEGATIVE') / NULLIF(COUNT(*), 0), 1)";

    private final JdbcClient jdbc;

    public record Totals(long total, Double avgFirstResponseMin, Double avgResolveHour, Double slaBreachRate,
            Double negativeRate, Double avgRating) {
    }

    public record CategoryStat(String category, long count, Double avgResolveHour, Double negativeRate) {
    }

    public Totals totals(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS total,
                               ROUND(AVG(EXTRACT(EPOCH FROM (first_responded_at - created_at)) / 60)::numeric, 1)
                                   AS avg_first_response_min,
                               %s AS avg_resolve_hour,
                               ROUND(100.0 * COUNT(*) FILTER (WHERE sla_breached) / NULLIF(COUNT(*), 0), 1)
                                   AS sla_breach_rate,
                               %s AS negative_rate,
                               ROUND(AVG(s.rating)::numeric, 1) AS avg_rating
                        FROM ticket t
                        LEFT JOIN survey s ON s.ticket_id = t.ticket_id
                        WHERE %s""".formatted(AVG_RESOLVE_HOUR, NEGATIVE_RATE, PERIOD))
                .param("from", from).param("to", to)
                .query(Totals.class).single();
    }

    /** 유형별 (많은 순) */
    public List<CategoryStat> byCategory(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql("""
                        SELECT category, COUNT(*) AS count, %s AS avg_resolve_hour, %s AS negative_rate
                        FROM ticket
                        WHERE %s
                        GROUP BY category ORDER BY count DESC, category""".formatted(AVG_RESOLVE_HOUR, NEGATIVE_RATE,
                        PERIOD))
                .param("from", from).param("to", to)
                .query(CategoryStat.class).list();
    }
}
