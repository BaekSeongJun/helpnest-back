// @owner PMJ
package com.helpnest.domain.assignment.service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.assignment.error.AssignErrorCode;
import com.helpnest.domain.assignment.repository.LeadLookupRepository;
import com.helpnest.domain.member.port.MemberInfo;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.event.TicketAssignedEvent;
import com.helpnest.domain.ticket.repository.AgentLoad;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 배정 (PRD 6.2, FR-ASN-01~02). docs/02 §5.3 시퀀스가 AssignmentService 를 별도
 * participant 로 그리므로 티켓 도메인과 분리된 패키지에 둔다.
 *
 * <h2>동시성</h2>
 * 대상 티켓 1행만 {@code SELECT ... FOR UPDATE} 로 잠근다. 부하 집계는 락 밖의 읽기이므로
 * 두 티켓이 동시에 배정될 때 같은 상담원을 고를 수 있다 — 분산이 드물게 1건 치우치는 정도이고
 * <b>중복 배정(한 티켓에 두 번 배정)은 발생하지 않는다</b>. 상담원 전체를 잠그면 접수 처리량이
 * 상담원 수에 묶이므로 이 트레이드오프를 택했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssignmentService {

    /** docs/03 §2.1 notification.type. ASSIGNED 알림은 TicketAssignedEvent 구독자(S2)가 보낸다 */
    private static final String TYPE_UNASSIGNED = "UNASSIGNED";

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final LeadLookupRepository leadLookupRepository;
    private final MemberQueryPort memberQueryPort;
    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 최소 부하 자동 배정 (PRD 6.2). 분류 직후와 팀장의 "자동 배정 재시도"에서 호출한다.
     *
     * <p>이미 배정된 티켓은 건드리지 않는다 — status 가 RECEIVED 가 아니면 그냥 돌아간다.
     * 이 가드가 S3 의 FR-CHT-06(채팅으로 이미 배정된 티켓은 분류 시 재배정하지 않음)도 함께
     * 만족시킨다. 후보가 없을 때도 예외를 던지지 않고 RECEIVED 로 남긴 뒤 팀장에게 알린다 —
     * 상담원이 모두 자리를 비운 것은 고객 잘못이 아니므로 접수는 성공해야 한다.
     *
     * @return 배정된 상담원의 member_id. 배정하지 않았으면 null
     */
    @Transactional
    public Long autoAssign(Long ticketId) {
        Ticket ticket = lockTicket(ticketId);
        if (ticket.getStatus() != TicketStatus.RECEIVED) {
            log.debug("[assign] 이미 배정됨 ticketId={} status={}", ticketId, ticket.getStatus());
            return null;
        }

        List<MemberInfo> candidates = memberQueryPort.findAssignableAgents();
        if (candidates.isEmpty()) {
            notifyLeads(ticketId, ticket.getTicketNo());
            log.info("[assign] 가용 상담원 없음 ticketNo={} — RECEIVED 유지", ticket.getTicketNo());
            return null;
        }

        MemberInfo winner = pickLeastLoaded(candidates);
        apply(ticket, winner.memberId(), null, null, HistoryAction.ASSIGN, ActorType.SYSTEM, null);
        log.info("[assign] 자동 배정 ticketNo={} agentId={}", ticket.getTicketNo(), winner.memberId());
        return winner.memberId();
    }

    /**
     * 수동 배정·재배정 (FR-ASN-02). 최초 배정과 재배정을 {@code HistoryAction} 으로 구분한다 —
     * 팀장이 "누가 왜 넘겼는지"를 이력에서 봐야 하므로 REASSIGN 은 이전 담당자를 fromValue 에 남긴다.
     *
     * @param actorId 수행한 팀장·관리자의 member_id (이력의 actor)
     */
    @Transactional
    public void assignTo(Long ticketId, Long newAgentId, String memo, Long actorId) {
        MemberInfo agent = memberQueryPort.getMember(newAgentId);
        if (!"AGENT".equals(agent.role())) {
            throw new BusinessException(AssignErrorCode.NOT_AGENT);
        }

        Ticket ticket = lockTicket(ticketId);
        Long previousAgentId = ticket.getAgentId();
        boolean reassign = previousAgentId != null;

        apply(ticket, newAgentId,
                reassign ? String.valueOf(previousAgentId) : null,
                memo,
                reassign ? HistoryAction.REASSIGN : HistoryAction.ASSIGN,
                ActorType.MEMBER, actorId);
        log.info("[assign] 수동 {} ticketNo={} {} -> {}", reassign ? "재배정" : "배정",
                ticket.getTicketNo(), previousAgentId, newAgentId);
    }

    private Ticket lockTicket(Long ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
    }

    /**
     * 부하가 가장 적은 상담원. 동률이면 {@code last_assigned_at} 이 오래된 쪽을 고른다 —
     * 한 번도 배정받지 않은 상담원(null)이 가장 먼저다(nullsFirst). 그래도 동률이면 member_id
     * 순으로 고정해 결과가 호출마다 흔들리지 않게 한다(테스트 재현성).
     */
    private MemberInfo pickLeastLoaded(List<MemberInfo> candidates) {
        List<Long> ids = candidates.stream().map(MemberInfo::memberId).toList();
        Map<Long, Long> loads = ticketRepository.countActiveByAgentIds(ids).stream()
                .collect(Collectors.toMap(AgentLoad::agentId, AgentLoad::activeCount));

        Comparator<MemberInfo> byLoad = Comparator.comparingLong(
                (MemberInfo m) -> loads.getOrDefault(m.memberId(), 0L));
        Comparator<MemberInfo> byLastAssigned = Comparator.comparing(MemberInfo::lastAssignedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()));

        return candidates.stream()
                .min(byLoad.thenComparing(byLastAssigned).thenComparing(MemberInfo::memberId))
                .orElseThrow(() -> new BusinessException(AssignErrorCode.NO_AVAILABLE_AGENT));
    }

    /** 배정 반영 + 이력 + last_assigned_at 갱신 + 이벤트. 네 가지가 항상 함께 일어나야 한다 */
    private void apply(Ticket ticket, Long agentId, String fromValue, String memo,
            HistoryAction action, ActorType actorType, Long actorId) {
        ticket.assignTo(agentId, OffsetDateTime.now());

        ticketHistoryRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action(action)
                .fromValue(fromValue)
                .toValue(String.valueOf(agentId))
                .actorId(actorId)
                .actorType(actorType)
                .memo(memo)
                .build());

        // 다음 배정의 동률 판정 기준이므로 배정 직후에 갱신해야 한다
        memberQueryPort.touchLastAssigned(agentId);
        eventPublisher.publishEvent(new TicketAssignedEvent(ticket.getId(), agentId));
    }

    /**
     * 가용 상담원이 없을 때 팀장·관리자에게 알린다.
     *
     * <p>알림 저장·발송은 S2 의 NotificationPort 구현이 맡으므로 지금은 스텁 로그만 남는다.
     * 그래도 호출을 지금 넣는 이유는, S2 에 어댑터만 구현하면 이 경로가 바로 동작하고
     * "미배정 티켓이 조용히 쌓이는" 상태를 만들지 않기 때문이다.
     */
    private void notifyLeads(Long ticketId, String ticketNo) {
        List<Long> leadIds = leadLookupRepository.findLeadMemberIds();
        if (leadIds.isEmpty()) {
            log.warn("[assign] 미배정 알림 수신자(LEAD·ADMIN)가 없다 ticketNo={}", ticketNo);
            return;
        }
        String message = "배정할 상담원이 없어 미배정 상태입니다. (%s)".formatted(ticketNo);
        leadIds.forEach(leadId -> notificationPort.notify(leadId, TYPE_UNASSIGNED, ticketId, message));
    }
}
