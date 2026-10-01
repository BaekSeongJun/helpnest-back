// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ai.port.AiResultPort;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Sentiment;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 분류 반영 — AI 결과(비동기)와 상담원 수동 수정이 같은 로직을 쓴다.
 *
 * <h2>우선순위가 바뀌면 SLA 기한을 다시 계산한다</h2>
 * PRD 6.1 이 "AI 분류로 우선순위가 바뀌면 재계산"을 요구한다. 기준 시각은 <b>접수 시각</b>이다 —
 * 분류 시각으로 계산하면 LLM 이 느릴 때마다 기한이 뒤로 밀려 SLA 를 피할 수 있게 된다.
 * {@code Ticket.applyClassification} 이 기한을 건드리지 않는 것은 SlaPolicy 조회가 필요해
 * 엔티티 혼자 할 수 없기 때문이고, 두 호출을 묶는 것이 이 서비스의 역할이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketClassificationService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final AiResultPort aiResultPort;

    /**
     * AI 분류 결과 반영. 수행자가 사람이 아니므로 이력의 actorType 은 SYSTEM 이다.
     *
     * @param category  null 이면 분류하지 않은 것으로 보고 기존 값을 유지한다
     * @param priority  null 이면 기존 값 유지
     * @param sentiment null 이면 기존 값 유지
     */
    @Transactional
    public void applyAiResult(Long ticketId, TicketCategory category, TicketPriority priority,
            Sentiment sentiment) {
        Ticket ticket = lockTicket(ticketId);
        TicketPriority oldPriority = ticket.getPriority();

        ticket.applyClassification(
                category != null ? category : ticket.getCategory(),
                priority != null ? priority : oldPriority,
                sentiment != null ? sentiment : ticket.getSentiment());

        if (ticket.getPriority() != oldPriority) {
            recalculateDueAt(ticket);
            saveHistory(ticket, HistoryAction.PRIORITY_CHANGE, oldPriority.name(),
                    ticket.getPriority().name(), null, ActorType.SYSTEM, null);
        }
        log.info("[classification] AI 반영 ticketNo={} category={} priority={} sentiment={}",
                ticket.getTicketNo(), ticket.getCategory(), ticket.getPriority(), ticket.getSentiment());
    }

    /**
     * 상담원·팀장의 수동 분류 수정 (docs/04 §7, docs/05 §3.3).
     *
     * <p>권한은 담당 AGENT 또는 LEAD+ 다. {@code leadOrAbove} 가 false 면 본인 담당 티켓인지
     * 확인한다 — 이 검증을 빠뜨리면 상담원이 남의 티켓 우선순위를 올려 SLA 순서를 흔들 수 있다.
     *
     * <p>감정(sentiment)은 수정 대상이 아니다. docs/04 §7 요청 본문이 {@code {category, priority}}
     * 뿐이고, 불만 표시는 LLM 판정 기록이라 사람이 지우면 통계가 왜곡된다.
     *
     * @param leadOrAbove 수행자가 LEAD 또는 ADMIN 인지
     */
    @Transactional
    public void applyManualUpdate(Long ticketId, TicketCategory category, TicketPriority priority,
            Long actorId, boolean leadOrAbove) {
        Ticket ticket = lockTicket(ticketId);
        if (!leadOrAbove && !actorId.equals(ticket.getAgentId())) {
            throw new BusinessException(TicketErrorCode.NOT_ASSIGNEE);
        }

        TicketCategory oldCategory = ticket.getCategory();
        TicketPriority oldPriority = ticket.getPriority();
        ticket.applyClassification(category, priority, ticket.getSentiment());

        if (category != oldCategory) {
            saveHistory(ticket, HistoryAction.CATEGORY_CHANGE, oldCategory.name(), category.name(),
                    null, ActorType.MEMBER, actorId);
        }
        if (priority != oldPriority) {
            recalculateDueAt(ticket);
            saveHistory(ticket, HistoryAction.PRIORITY_CHANGE, oldPriority.name(), priority.name(),
                    null, ActorType.MEMBER, actorId);
        }

        // AI 결과를 사람이 고쳤다는 기록 (overridden_by). 신수진 도메인의 정확도 측정 입력이다
        aiResultPort.markOverridden(ticketId, actorId, category.name(), priority.name());
        log.info("[classification] 수동 수정 ticketNo={} by={} category={} priority={}",
                ticket.getTicketNo(), actorId, category, priority);
    }

    private Ticket lockTicket(Long ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
    }

    /** 바뀐 우선순위의 정책으로 접수 시각 기준 기한을 다시 계산한다 */
    private void recalculateDueAt(Ticket ticket) {
        ticket.updateFirstResponseDueAt(slaPolicyRepository.findById(ticket.getPriority())
                .orElseThrow(() -> new IllegalStateException(
                        "sla_policy 에 %s 정책이 없습니다.".formatted(ticket.getPriority())))
                .calculateDueAt(ticket.getCreatedAt()));
    }

    private void saveHistory(Ticket ticket, HistoryAction action, String fromValue, String toValue,
            String memo, ActorType actorType, Long actorId) {
        ticketHistoryRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action(action)
                .fromValue(fromValue)
                .toValue(toValue)
                .memo(memo)
                .actorType(actorType)
                .actorId(actorId)
                .build());
    }

    /**
     * 포트로 들어온 문자열을 enum 으로 변환한다. 목록 밖 값이면
     * {@code TICKET_INVALID_CLASSIFICATION} 으로 거부한다 — ticket 테이블에 CHECK 제약이 없어
     * (docs/03 §3.2) 이 변환이 유일한 방어선이다.
     *
     * @param value null·빈 문자열이면 null 을 돌려준다("판정하지 않음")
     * @param field 어느 값이 틀렸는지 알려 주는 로그·메시지용 이름
     */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(TicketErrorCode.INVALID_CLASSIFICATION,
                    "%s 값이 올바르지 않습니다: %s".formatted(field, value));
        }
    }
}
