// @owner PMJ
package com.helpnest.domain.ticket.port;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.assignment.service.AssignmentService;
import com.helpnest.domain.ticket.entity.Sentiment;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.service.TicketClassificationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 분류 결과를 티켓에 반영하는 포트 구현 (docs/02 §5.2, docs/05 §3.1).
 *
 * <p>호출자는 신수진의 {@code AiClassifyListener} 이며 {@code TicketCreatedEvent} 를
 * AFTER_COMMIT + @Async 로 받은 비동기 스레드에서 들어온다. 그래서 주변에 트랜잭션이 없고
 * 이 계층이 트랜잭션 경계를 연다.
 *
 * <h2>문자열을 받는 이유</h2>
 * 포트 시그니처가 enum 이 아니라 String 인 것은 호출자가 내 도메인 타입을 import 하지 않게
 * 하려는 설계다({@link TicketClassificationPort}). 대신 목록 밖 값을 걸러 낼 책임이 이쪽에
 * 생긴다 — ticket 테이블에 CHECK 제약이 없어(docs/03 §3.2) 여기서 막지 않으면 DB 에
 * 알 수 없는 유형이 들어간다.
 *
 * <h2>얇게 유지하는 이유</h2>
 * 실제 반영은 {@link TicketClassificationService} 가 한다. 포트 구현은 "문자열 → enum 변환"과
 * "서비스 호출"만 맡는다. 같은 반영 로직을 상담원 수동 수정 API 도 써야 하므로 로직이 포트
 * 구현에 들어 있으면 컨트롤러가 포트를 호출하는 거꾸로 된 모양이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketClassificationAdapter implements TicketClassificationPort {

    private final TicketClassificationService classificationService;
    private final AssignmentService assignmentService;

    @Override
    @Transactional
    public void applyClassification(Long ticketId, String category, String priority, String sentiment) {
        classificationService.applyAiResult(ticketId,
                toEnum(TicketCategory.class, category, "category"),
                toEnum(TicketPriority.class, priority, "priority"),
                toEnum(Sentiment.class, sentiment, "sentiment"));
        assignmentService.autoAssign(ticketId);
    }

    /**
     * 분류 실패 시 (docs/05 §3.1 7번). 기본값(ETC·NORMAL)을 그대로 두고 배정만 진행한다 —
     * LLM 이 죽었다고 고객 문의가 미배정으로 방치되면 안 된다.
     */
    @Override
    @Transactional
    public void applyClassificationFailed(Long ticketId) {
        log.info("[classification] 분류 실패 — 기본값 유지하고 배정만 진행 ticketId={}", ticketId);
        assignmentService.autoAssign(ticketId);
    }

    /**
     * 문자열을 enum 으로. null 은 "판정하지 않음"이라 그대로 통과시키고, 값이 있지만 목록 밖이면
     * 거부한다.
     *
     * @throws com.helpnest.global.error.BusinessException TICKET_INVALID_CLASSIFICATION
     */
    private static <E extends Enum<E>> E toEnum(Class<E> type, String value, String field) {
        return TicketClassificationService.parseEnum(type, value, field);
    }
}
