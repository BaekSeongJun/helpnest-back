// @owner PMJ
package com.helpnest.domain.ticket.dto;

import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * 콘솔 티켓 목록 검색 조건 (docs/04 §7 GET /api/console/tickets, 화면 CS-01).
 *
 * <p>모든 필드가 null 이면 조건 없음이다. 필드를 개별 파라미터로 받지 않고 record 로 묶은 것은
 * 컨트롤러 → 서비스 → Specification 세 곳에 같은 7개 인자를 늘어놓지 않기 위해서다.
 *
 * <h2>agentId 와 unassigned 는 상호 배타다</h2>
 * {@code agentId} 는 "그 상담원 담당분", {@code unassigned} 는 "담당자가 없는 분"이라 동시에
 * 참일 수 없다. 문자열 센티넬({@code agentId=none})을 쓰지 않은 이유는 {@code Long} 타입을
 * 오염시키기 때문이다(CS-01 태스크 결정). 둘이 함께 오면 {@code unassigned} 를 우선한다.
 *
 * @param unassigned true 면 담당자가 없는 티켓만. null·false 면 조건 없음
 */
public record TicketSearchCondition(
        TicketStatus status,
        TicketPriority priority,
        TicketCategory category,
        Long agentId,
        Boolean unassigned,
        SlaFilter sla,
        String keyword) {

    public boolean isUnassignedOnly() {
        return Boolean.TRUE.equals(unassigned);
    }

    /**
     * 상담원 본인 담당분으로 조건을 좁힌다. AGENT 가 보낸 {@code agentId} 는 <b>검증하지 않고
     * 덮어쓴다</b> — 거부(403)하면 "그 상담원이 존재한다"는 사실이 드러나고, 조건을 강제하는
     * 쪽이 누락 위험이 없다.
     *
     * <p>{@code unassigned} 요청은 그대로 둔다. 미배정 티켓은 누구의 것도 아니므로 상담원이
     * 미배정 대기열을 보는 것은 CS-01 의 '미배정' 탭 그대로이며(docs/09), 이때 담당자 조건을
     * 함께 걸면 결과가 항상 비어 버린다.
     */
    public TicketSearchCondition restrictedTo(Long actorId) {
        return isUnassignedOnly()
                ? new TicketSearchCondition(status, priority, category, null, true, sla, keyword)
                : new TicketSearchCondition(status, priority, category, actorId, false, sla, keyword);
    }
}
