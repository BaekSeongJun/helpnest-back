// @owner PMJ
package com.helpnest.domain.ticket.port;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * S0 스텁 — 실제 조회는 <b>S1</b> 에서 구현한다.
 *
 * <p>두 메서드 모두 반환값이 있어 호출자가 미구현을 바로 알 수 있으므로 로그는 debug 로 둔다.
 *
 * <p>TODO(PMJ) S1 getTicketSummary 구현 — TicketRepository.findById 로 조회해
 * {@code new TicketSummary(title, content, category.name())} 을 만든다. 없으면
 * BusinessException(TicketErrorCode.NOT_FOUND). 본문은 마스킹하지 않고 원문을 넘긴다
 * (마스킹은 호출자 책임, {@link TicketSummary} 주석 참고).
 *
 * <p>TODO(PMJ) S1 findResolvedReplies 구현 — docs/05 §4.1 은 "같은 유형 + RESOLVED/CLOSED +
 * 만족도 4 이상 우선"을 요구하는데 만족도는 백성준의 SURVEY 테이블에 있다. docs/02 §5 의
 * 읽기 전용 예외 규정(자기 패키지의 읽기 전용 Native Query/JPQL 로 다른 테이블 JOIN 허용,
 * 쓰기는 반드시 포트)을 근거로 ticket·ticket_reply·survey 를 JOIN 한다. PR 본문에 참조 컬럼
 * 목록을 적어 백성준이 스키마 변경 시 알 수 있게 해야 한다(같은 규정). 조건:
 * ticket.category = :category, ticket.status IN ('RESOLVED','CLOSED'),
 * ticket_reply.is_internal = false, ticket_reply.writer_type = 'AGENT',
 * 정렬은 survey.rating DESC NULLS LAST, ticket_reply.created_at DESC, LIMIT :limit.
 */
@Slf4j
@Component
public class TicketQueryAdapter implements TicketQueryPort {

    @Override
    public TicketSummary getTicketSummary(Long ticketId) {
        log.debug("[stub] getTicketSummary ticketId={}", ticketId);
        return null;
    }

    @Override
    public List<ResolvedReply> findResolvedReplies(String category, int limit) {
        log.debug("[stub] findResolvedReplies category={} limit={}", category, limit);
        return List.of();
    }
}
