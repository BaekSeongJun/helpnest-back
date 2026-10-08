// @owner BSJ
package com.helpnest.domain.dashboard.agent;

import com.helpnest.domain.dashboard.agent.AgentDetail.TeamAverage;
import com.helpnest.domain.dashboard.dto.AgentStat;
import com.helpnest.domain.dashboard.dto.Period;
import com.helpnest.domain.dashboard.service.DashboardService;
import com.helpnest.domain.member.port.MemberQueryPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 상담원 개인 상세 (docs/04 §13.x). 본인 행·팀 목록은 신수진 {@link DashboardService} 를 그대로 읽는다 */
@Service
public class AgentDetailService {

    private final DashboardService dashboardService;
    private final AgentDetailQueryRepository repository;
    private final MemberQueryPort memberQueryPort;
    private final Clock clock;

    @Autowired
    public AgentDetailService(DashboardService dashboardService, AgentDetailQueryRepository repository,
            MemberQueryPort memberQueryPort) {
        this(dashboardService, repository, memberQueryPort, Clock.systemUTC());
    }

    AgentDetailService(DashboardService dashboardService, AgentDetailQueryRepository repository,
            MemberQueryPort memberQueryPort, Clock clock) {
        this.dashboardService = dashboardService;
        this.repository = repository;
        this.memberQueryPort = memberQueryPort;
        this.clock = clock;
    }

    public AgentDetail detail(Long agentId, Period period) {
        memberQueryPort.getMember(agentId); // 없으면 404 MEMBER_NOT_FOUND
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = period.start(now);
        return new AgentDetail(
                dashboardService.me(agentId, period),
                teamAverage(dashboardService.agents(period)),
                repository.daily(agentId, from, now, from.atZoneSameInstant(Period.SEOUL).toLocalDate(),
                        now.atZoneSameInstant(Period.SEOUL).toLocalDate()),
                repository.byCategory(agentId, from, now),
                repository.byPriority(agentId, from, now),
                repository.tickets(agentId, from, now),
                repository.surveys(agentId, from, now));
    }

    static TeamAverage teamAverage(List<AgentStat> agents) {
        return new TeamAverage(agents.size(),
                count(agents, AgentStat::assignedCount),
                count(agents, AgentStat::inProgressCount),
                count(agents, AgentStat::resolvedToday),
                mean(agents, AgentStat::avgFirstResponseMin, 1),
                mean(agents, AgentStat::avgResolveHour, 2),
                mean(agents, AgentStat::slaBreachRate, 1),
                mean(agents, AgentStat::avgRating, 1));
    }

    private static double count(List<AgentStat> agents, ToLongFunction<AgentStat> f) {
        return agents.isEmpty() ? 0 : round(agents.stream().mapToLong(f).average().orElse(0), 1);
    }

    private static Double mean(List<AgentStat> agents, Function<AgentStat, Double> f, int scale) {
        List<Double> values = agents.stream().map(f).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : round(values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(), scale);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
