// @owner PMJ
package com.helpnest.domain.sla.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.assignment.repository.LeadLookupRepository;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.repository.TicketSpecs;

import lombok.extern.slf4j.Slf4j;

/**
 * 첫 응답 SLA 감시 (PRD 6.1): 기한 임박·초과를 찾아 티켓당 한 번씩 알린다.
 *
 * <h2>플래그가 "이미 알렸다"를 기억한다</h2>
 * 대상 조회가 {@code sla_warned = false} / {@code sla_breached = false} 를 조건에 넣고, 알린 뒤
 * 플래그를 켠다. 따라서 폴링이 몇 번 돌아도 티켓당 알림은 한 번이다. 발송 시각을 따로 들고
 * 비교하는 방식을 쓰지 않는 이유는 {@code DefaultMailSender} 의 10분 묶음과 같다 — 기준이 두
 * 군데면 어긋날 때 중복이 나간다.
 *
 * <h2>임박과 위반을 한 회차에 둘 다 받지 않는다</h2>
 * 임박 조회에 {@code first_response_due_at >= now} 가 들어 있어, 기한이 이미 지난 티켓은 위반
 * 조회만 가져간다. 스케줄러가 멈춰 있다 재기동해 임박을 건너뛴 티켓은 위반 알림만 받는데,
 * 이미 넘긴 기한에 "임박했습니다"를 보내는 것이 더 이상하므로 의도한 동작이다.
 *
 * <h2>임박 판정식은 여기에 없다</h2>
 * {@link TicketSpecs#warningCutoff} 를 그대로 호출한다. 콘솔 목록의 임박 배지가 같은 식을 쓰므로
 * 여기서 비율 곱셈을 다시 구현하면 "목록엔 임박인데 알림은 안 오는" 어긋남이 생긴다.
 */
@Slf4j
@Component
public class SlaScheduler {

    /**
     * 더 감시하지 않는 상태. 해결·종료된 티켓에 "기한 임박"을 보내면 잘못된 알림이다.
     *
     * <p>콘솔 목록의 SLA 필터는 상태를 보지 않는데({@code TicketSpecs.sla}) 그쪽은 "지금 어떤
     * 상태인가"를 보여 주는 조회이고, 이쪽은 사람을 부르는 알림이라 기준이 다르다.
     */
    private static final Set<TicketStatus> DONE = EnumSet.of(TicketStatus.RESOLVED, TicketStatus.CLOSED);

    private final TicketRepository ticketRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final LeadLookupRepository leadLookupRepository;
    private final NotificationPort notificationPort;
    private final Clock clock;

    @Autowired
    public SlaScheduler(TicketRepository ticketRepository, SlaPolicyRepository slaPolicyRepository,
            LeadLookupRepository leadLookupRepository, NotificationPort notificationPort) {
        this(ticketRepository, slaPolicyRepository, leadLookupRepository, notificationPort, Clock.systemUTC());
    }

    /** 테스트가 시각을 고정하려고 쓰는 생성자 ({@code DefaultMailSender} 와 같은 방식) */
    SlaScheduler(TicketRepository ticketRepository, SlaPolicyRepository slaPolicyRepository,
            LeadLookupRepository leadLookupRepository, NotificationPort notificationPort, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.slaPolicyRepository = slaPolicyRepository;
        this.leadLookupRepository = leadLookupRepository;
        this.notificationPort = notificationPort;
        this.clock = clock;
    }

    /**
     * 위반을 먼저, 임박을 뒤에 훑는다. 두 조회는 겹치지 않지만(클래스 주석) 기한을 넘긴 쪽이
     * 더 급하므로 먼저 보낸다.
     *
     * <p>{@code @Transactional} 이라 {@code markSlaWarned}·{@code markSlaBreached} 는 변경 감지로
     * 저장된다 — save 호출이 없는 것은 누락이 아니다. 알림 저장도 같은 트랜잭션이므로 중간에
     * 실패하면 플래그와 알림이 함께 롤백되어 다음 회차에 다시 잡힌다.
     */
    // ponytail: fixedDelay 라 단일 인스턴스에서만 중복이 없다 — 다중 인스턴스 배포 시 ShedLock 등 분산 락
    //           (MailRetryScheduler 와 같은 한계)
    @Scheduled(fixedDelayString = "${app.sla.scan-delay:1m}", initialDelayString = "${app.sla.scan-delay:1m}")
    @Transactional
    public void scan() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 대상이 없을 때도 한 번 도는 가벼운 조회다. 필요할 때만 읽으려고 null 로 들고 다니면
        // 두 메서드가 공유하는 가변 상태가 생긴다 — 쿼리 한 번이 그보다 싸다
        List<Long> leadIds = leadLookupRepository.findLeadMemberIds();
        scanBreached(now, leadIds);
        scanWarning(now, leadIds);
    }

    /** 수신자는 담당 상담원과 팀장이다 (docs/03 §2.1 SLA_BREACHED) */
    private void scanBreached(OffsetDateTime now, List<Long> leadIds) {
        // ponytail: 한 회차에 전부 가져온다 — 스케줄러가 오래 멈췄다 재기동하면 밀린 만큼 한 번에 읽는다.
        //           건수가 문제가 되면 MailRetryScheduler 처럼 상한을 두고 다음 회차로 넘긴다
        for (Ticket ticket : ticketRepository.findSlaBreachTargets(now, DONE)) {
            ticket.markSlaBreached();
            notify(ticket, NotificationType.SLA_BREACHED, withLeads(ticket.getAgentId(), leadIds),
                    "첫 응답 기한을 넘겼습니다. (%s) %s".formatted(ticket.getTicketNo(), ticket.getTitle()));
        }
    }

    /** 수신자는 담당 상담원이다. 우선순위마다 임박 시각이 달라 정책 4행을 각각 조회한다 */
    private void scanWarning(OffsetDateTime now, List<Long> leadIds) {
        for (SlaPolicy policy : slaPolicyRepository.findAll()) {
            List<Ticket> targets = ticketRepository.findSlaWarningTargets(
                    policy.getPriority(), TicketSpecs.warningCutoff(policy, now), now, DONE);
            for (Ticket ticket : targets) {
                ticket.markSlaWarned();
                notify(ticket, NotificationType.SLA_WARNING, recipientsForWarning(ticket, leadIds),
                        "첫 응답 기한이 임박했습니다. (%s) %s".formatted(ticket.getTicketNo(), ticket.getTitle()));
            }
        }
    }

    /**
     * 임박은 담당 상담원에게 보내되, <b>미배정이면 팀장에게 보낸다.</b>
     *
     * <p>받을 사람이 없다고 건너뛰면 {@code sla_warned} 를 켤 수 없어 같은 티켓이 폴링마다 다시
     * 잡히고, 플래그만 켜고 넘기면 나중에 배정된 상담원이 임박을 영영 모른다. 둘 다 피하려면
     * 지금 조치할 수 있는 사람에게 보내야 하는데, 미배정 티켓의 담당자는 팀장이다
     * ({@code AssignmentService.notifyLeads} 의 UNASSIGNED 와 같은 수신자).
     */
    private static List<Long> recipientsForWarning(Ticket ticket, List<Long> leadIds) {
        return ticket.getAgentId() != null ? List.of(ticket.getAgentId()) : leadIds;
    }

    /** 담당 상담원이 팀장을 겸하면 한 통만 가게 중복을 없앤다 */
    private static List<Long> withLeads(Long agentId, List<Long> leadIds) {
        return Stream.concat(Stream.ofNullable(agentId), leadIds.stream()).distinct().toList();
    }

    private void notify(Ticket ticket, NotificationType type, List<Long> receiverIds, String message) {
        if (receiverIds.isEmpty()) {
            log.warn("[sla] {} 알림 수신자가 없다 ticketNo={}", type, ticket.getTicketNo());
            return;
        }
        receiverIds.forEach(receiverId -> notificationPort.notify(receiverId, type.name(), ticket.getId(), message));
        log.info("[sla] {} ticketNo={} 수신자={}", type, ticket.getTicketNo(), receiverIds.size());
    }
}
