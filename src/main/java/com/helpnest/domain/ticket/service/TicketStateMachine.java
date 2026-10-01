// @owner PMJ
package com.helpnest.domain.ticket.service;

import static com.helpnest.domain.ticket.entity.ActorRole.ADMIN;
import static com.helpnest.domain.ticket.entity.ActorRole.AGENT;
import static com.helpnest.domain.ticket.entity.ActorRole.CUSTOMER;
import static com.helpnest.domain.ticket.entity.ActorRole.LEAD;
import static com.helpnest.domain.ticket.entity.ActorRole.SYSTEM;
import static com.helpnest.domain.ticket.entity.TicketStatus.ASSIGNED;
import static com.helpnest.domain.ticket.entity.TicketStatus.CLOSED;
import static com.helpnest.domain.ticket.entity.TicketStatus.IN_PROGRESS;
import static com.helpnest.domain.ticket.entity.TicketStatus.RECEIVED;
import static com.helpnest.domain.ticket.entity.TicketStatus.RESOLVED;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 티켓 상태 전이 판정기. PRD 5장 전이표를 코드로 옮긴 단일 권위 지점이다.
 *
 * <h2>책임 경계</h2>
 * 이 클래스는 <b>판정만</b> 한다. 예외를 던지지 않고 허용 여부를 그대로 반환하므로
 * 상태를 바꾸지도, 이력을 남기지도, 이벤트를 발행하지도 않는다. 전이 거부를 에러 응답으로
 * 바꾸는 책임은 S1 의 TicketService 에 있다(docs/10 §3.3 "상태 전이·권한 검증은 Service에서").
 *
 * <pre>
 * // S1 TicketService 조립 예시
 * if (!stateMachine.isAllowedFor(ticket.getStatus(), to, actorRole)) {
 *     throw new BusinessException(TicketErrorCode.INVALID_TRANSITION);
 * }
 * </pre>
 *
 * <p><b>이 클래스가 판정하지 않는 것</b> — PRD 5장은 수행자를 "담당 AGENT"로 적고 있으나,
 * 어떤 상담원이 그 티켓의 담당인지({@code ticket.agentId == actorId})는 티켓 데이터를 봐야
 * 알 수 있고 이 클래스는 티켓을 인자로 받지 않는다. 따라서 여기서는 <b>역할만</b> 판정하고,
 * 본인 담당 여부 검증은 TicketService 가 별도로 수행해야 한다. 이 경계를 넘겨 AGENT 판정을
 * 통과했다는 사실만으로 남의 티켓을 처리하게 두면 권한 우회가 된다.
 *
 * <p>TODO(PMJ): S1 에서 TicketErrorCode.INVALID_TRANSITION(TICKET_INVALID_TRANSITION, 409)
 * 을 추가하고 위 예시대로 조립한다(PRD FR-TKT-03, global/error/ErrorCode Javadoc 규약).
 */
@Component
public class TicketStateMachine {

    /**
     * {@code 현재 상태 → (다음 상태 → 그 전이를 수행할 수 있는 역할 집합)} 전이표.
     * 표에 없는 조합은 모두 불허이므로, 불허를 따로 열거하지 않는다.
     */
    private static final Map<TicketStatus, Map<TicketStatus, Set<ActorRole>>> TRANSITIONS = buildTransitions();

    /**
     * PRD 5장 전이표의 허용 전이 7건을 그대로 옮긴다. 주석은 전이 사유(전이표 stateDiagram 원문).
     *
     * <p>참고: 재배정 2건(→ ASSIGNED)의 수행자에 AGENT 가 포함된 것은 전이표 '수행자' 열이
     * 행 단위로 "담당 AGENT, LEAD/ADMIN"이라 적혀 있기 때문이다. 반면 PRD 2.1 권한 매트릭스와
     * FR-ASN-02 는 수동 배정/재배정을 LEAD/ADMIN 전용으로 규정한다. 문서 간 충돌이며,
     * 여기서는 태스크가 지정한 권위 문서인 5장 전이표를 따른다.
     * TODO(PMJ): 재배정 주체를 팀과 확정한 뒤 이 두 줄에서 AGENT 를 빼거나(2.1 기준)
     * S1 TicketService 의 배정 API 에서 LEAD/ADMIN 만 허용하도록 좁힌다.
     */
    private static Map<TicketStatus, Map<TicketStatus, Set<ActorRole>>> buildTransitions() {
        Map<TicketStatus, Map<TicketStatus, Set<ActorRole>>> table = new EnumMap<>(TicketStatus.class);
        for (TicketStatus from : TicketStatus.values()) {
            table.put(from, new EnumMap<>(TicketStatus.class));
        }

        table.get(RECEIVED).put(ASSIGNED, roles(SYSTEM, LEAD, ADMIN));    // 자동/수동 배정
        table.get(ASSIGNED).put(ASSIGNED, roles(AGENT, LEAD, ADMIN));     // 재배정
        table.get(ASSIGNED).put(IN_PROGRESS, roles(AGENT, LEAD, ADMIN));  // 처리 시작 / 첫 답변
        table.get(IN_PROGRESS).put(ASSIGNED, roles(AGENT, LEAD, ADMIN));  // 재배정
        table.get(IN_PROGRESS).put(RESOLVED, roles(AGENT, LEAD, ADMIN));  // 해결 처리
        table.get(RESOLVED).put(IN_PROGRESS, roles(SYSTEM, CUSTOMER));    // 고객 추가 답글(재문의)
        table.get(RESOLVED).put(CLOSED, roles(SYSTEM, CUSTOMER));         // 설문 제출 / 72시간 경과
        // CLOSED 는 의도적으로 비어 있다 — 종료 후에는 어떤 역할도 상태를 바꿀 수 없다(불변).

        // 중첩 Map 까지 읽기 전용으로 감싼다. 바깥만 감싸면 내부 Map 이 그대로 노출된다.
        Map<TicketStatus, Map<TicketStatus, Set<ActorRole>>> frozen = new EnumMap<>(TicketStatus.class);
        table.forEach((from, targets) -> frozen.put(from, Collections.unmodifiableMap(targets)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Set<ActorRole> roles(ActorRole first, ActorRole... rest) {
        return Collections.unmodifiableSet(EnumSet.of(first, rest));
    }

    /**
     * 역할과 무관하게 {@code from → to} 전이 자체가 전이표에 있는지 판정한다.
     * 목록 화면의 상태 드롭다운 구성처럼 수행자가 정해지지 않은 맥락에서 쓴다.
     *
     * @return 전이표에 없거나 인자가 {@code null} 이면 {@code false}
     */
    public boolean isAllowed(TicketStatus from, TicketStatus to) {
        return !allowedRoles(from, to).isEmpty();
    }

    /**
     * {@code from → to} 전이를 {@code actorRole} 이 수행할 수 있는지 판정한다.
     * 전이 자체가 불허이거나, 허용 전이라도 역할이 수행자 집합에 없으면 거부한다.
     *
     * <p>본인 담당 티켓인지는 판정하지 않는다(클래스 주석의 책임 경계 참고).
     *
     * @return 인자 중 하나라도 {@code null} 이면 {@code false}
     */
    public boolean isAllowedFor(TicketStatus from, TicketStatus to, ActorRole actorRole) {
        return actorRole != null && allowedRoles(from, to).contains(actorRole);
    }

    /**
     * {@code from} 에서 전이할 수 있는 다음 상태 전체를 반환한다.
     * 반환 순서는 {@link TicketStatus} 선언 순서(EnumMap 기준)이며, 반환 Set 은 수정할 수 없다.
     *
     * @return CLOSED 이거나 {@code from} 이 {@code null} 이면 빈 Set
     */
    public Set<TicketStatus> allowedNextStatuses(TicketStatus from) {
        if (from == null) {
            return Set.of();
        }
        return TRANSITIONS.get(from).keySet();
    }

    private Set<ActorRole> allowedRoles(TicketStatus from, TicketStatus to) {
        if (from == null || to == null) {
            return Set.of();
        }
        return TRANSITIONS.get(from).getOrDefault(to, Set.of());
    }
}
