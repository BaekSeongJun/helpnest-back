// @owner SSJ
package com.helpnest.domain.dashboard.service;

import com.helpnest.domain.dashboard.dto.AgentStat;
import com.helpnest.domain.dashboard.dto.DashboardSummary;
import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.domain.dashboard.repository.DashboardQueryRepository;
import com.helpnest.domain.dashboard.repository.DashboardQueryRepository.Kpi;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 대시보드 (docs/04 §13, FR-DSH-01~03). 시각은 Asia/Seoul 로 계산해 [start, now) 로 넘긴다 */
@Service
public class DashboardService {

    private final DashboardQueryRepository repository;
    private final Clock clock;

    @Autowired
    public DashboardService(DashboardQueryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    DashboardService(DashboardQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public DashboardSummary summary(Period period) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = period.start(now);
        Kpi kpi = repository.kpi(from, now);
        return new DashboardSummary(kpi.total(), repository.unassigned(), kpi.slaBreachRate(),
                kpi.avgFirstResponseMin(), kpi.avgRating(), repository.countByStatus(from, now),
                repository.countByCategory(from, now));
    }

    public List<AgentStat> agents(Period period) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.agents(period.start(now), now, Period.todayStart(now));
    }

    public AgentStat me(Long memberId, Period period) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.agent(memberId, period.start(now), now, Period.todayStart(now));
    }
}
