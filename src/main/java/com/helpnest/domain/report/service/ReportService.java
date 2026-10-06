// @owner SSJ
package com.helpnest.domain.report.service;

import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.domain.report.dto.MonthlyReport;
import com.helpnest.domain.report.dto.MonthlyReport.CategoryRow;
import com.helpnest.domain.report.repository.ReportQueryRepository;
import com.helpnest.domain.report.repository.ReportQueryRepository.CategoryStat;
import com.helpnest.domain.report.repository.ReportQueryRepository.Totals;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 월간 리포트 (docs/04 §13, FR-RPT-01). 월 경계는 Asia/Seoul 로 계산해 [월초, 다음 달 월초) 로 넘긴다 */
@Service
public class ReportService {

    private final ReportQueryRepository repository;
    private final Clock clock;

    @Autowired
    public ReportService(ReportQueryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ReportService(ReportQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @param month YYYY-MM, null 이면 이번 달(서울). 형식 오류는 400 */
    public MonthlyReport monthly(String month) {
        YearMonth ym = parse(month);
        YearMonth prev = ym.minusMonths(1);

        Totals totals = repository.totals(start(ym), start(ym.plusMonths(1)));
        List<CategoryStat> current = repository.byCategory(start(ym), start(ym.plusMonths(1)));
        List<CategoryStat> previous = repository.byCategory(start(prev), start(ym));

        Map<String, Long> prevCounts = new LinkedHashMap<>();
        previous.forEach(p -> prevCounts.put(p.category(), p.count()));

        List<CategoryRow> rows = new ArrayList<>();
        for (CategoryStat c : current) {
            Long prevCount = prevCounts.remove(c.category());
            rows.add(new CategoryRow(c.category(), c.count(), prevCount == null ? 0 : prevCount, c.avgResolveHour(),
                    c.negativeRate()));
        }
        // 전월에만 있던 유형도 행으로 남겨 감소가 보이게 한다
        prevCounts.forEach((category, prevCount) -> rows.add(new CategoryRow(category, 0, prevCount, null, null)));

        long prevTotal = previous.stream().mapToLong(CategoryStat::count).sum();
        return new MonthlyReport(ym.toString(), totals.total(), prevTotal, totals.avgFirstResponseMin(),
                totals.avgResolveHour(), totals.slaBreachRate(), totals.negativeRate(), totals.avgRating(), rows);
    }

    YearMonth parse(String month) {
        if (month == null) {
            return YearMonth.now(clock.withZone(Period.SEOUL));
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
    }

    static OffsetDateTime start(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(Period.SEOUL).toOffsetDateTime();
    }
}
