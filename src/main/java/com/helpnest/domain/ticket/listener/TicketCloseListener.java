// @owner PMJ
package com.helpnest.domain.ticket.listener;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.helpnest.domain.survey.event.SurveySubmittedEvent;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.service.TicketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 고객이 설문을 제출하면 해당 티켓을 CLOSED 로 전이한다
 * (docs/02 §5.1 {@code SurveySubmittedEvent} 의 구독자, PRD 5장 전이표 "설문 제출").
 *
 * <h2>전이를 직접 하지 않고 TicketService 에 넘긴다</h2>
 * 엔티티의 {@code changeStatusTo} 를 부르면 상태만 바뀌고 전이 검증·STATUS_CHANGE 이력·
 * {@code TicketStatusChangedEvent} 가 빠진다. 이력이 없으면 "이 티켓이 왜 종료됐는지"가
 * 영구히 사라지고, 이벤트가 없으면 종료 알림도 가지 않는다. {@code TicketReplyService} 가
 * ASSIGNED→IN_PROGRESS 를 직접 처리하지 않는 것과 같은 이유다.
 *
 * <h2>수행자는 SYSTEM 이다 (CUSTOMER 가 아니다)</h2>
 * 전이표는 RESOLVED→CLOSED 에 SYSTEM·CUSTOMER 를 모두 허용한다. 그래도 SYSTEM 을 쓰는 이유는
 * {@link SurveySubmittedEvent} 가 {@code ticketId}·{@code rating} 만 실어 <b>제출자의 member_id 를
 * 모르기</b> 때문이다(비회원 설문도 토큰으로 제출한다). actorId 없이 CUSTOMER 로 적으면 이력에
 * {@code actor_type=MEMBER} 인데 {@code actor_id=NULL} 인 행이 남아 "누가 했는지 모르는 회원 행위"가
 * 된다. 사람을 특정할 수 없는 전이는 시스템 전이로 기록하는 것이 맞다.
 *
 * <h2>실패해도 던지지 않는다</h2>
 * 설문 제출 트랜잭션은 이미 커밋됐다. {@code @Async} 스레드에서 예외를 던져도 제출이 되돌아가지
 * 않고 로그 없이 사라질 뿐이다({@code SurveyListener}·{@code NotificationListener} 와 같은 판단).
 * 게다가 종료가 영구히 누락되지도 않는다 — {@code AutoCloseScheduler} 가 해결 후 72시간이 지난
 * 같은 티켓을 다시 잡아 종료한다. 이 리스너는 "빨리 종료하는 길"이고 스케줄러가 최종 보장이다.
 *
 * <p>이미 CLOSED 이거나 재문의로 IN_PROGRESS 가 된 티켓이면 {@code changeStatus} 가
 * {@code TICKET_INVALID_TRANSITION} 을 던진다. 이는 장애가 아니라 "종료할 필요가 없어진 경우"이므로
 * 경고 한 줄로 끝낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketCloseListener {

    private final TicketService ticketService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(SurveySubmittedEvent event) {
        try {
            ticketService.changeStatus(event.ticketId(), TicketStatus.CLOSED, "설문 제출", null,
                    ActorRole.SYSTEM);
        } catch (Exception e) {
            log.warn("[ticket] 설문 제출 후 종료 실패 ticketId={} cause={}", event.ticketId(), e.toString());
        }
    }
}
