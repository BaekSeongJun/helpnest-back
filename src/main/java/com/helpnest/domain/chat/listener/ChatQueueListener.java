// @owner PMJ
package com.helpnest.domain.chat.listener;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.helpnest.domain.chat.scheduler.ChatQueueScheduler;
import com.helpnest.domain.member.event.AgentAvailabilityChangedEvent;

import lombok.RequiredArgsConstructor;

/**
 * 상담원이 상담 가능(ON)으로 바뀌면 채팅 대기열을 즉시 한 번 처리한다 (docs/02 §6, CR #90).
 * 이게 없으면 다음 스케줄러 주기(최대 10초)까지 대기 고객이 연결되지 않는다.
 *
 * <p>AFTER_COMMIT — 커밋 전이면 배정 후보 조회가 아직 OFF 인 상담원을 본다.
 * {@code @Async} — 대기열 처리(방마다 트랜잭션)가 토글 API 응답을 붙잡지 않게 한다. 스케줄러와
 * 동시에 돌아도 방 1행 비관적 락과 WAITING 재확인({@code ChatService.tryMatch})으로 한 번만 연결된다.
 * OFF 로 바뀐 경우는 할 일이 없다 — 이미 연결된 상담은 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class ChatQueueListener {

    private final ChatQueueScheduler chatQueue;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAvailabilityChanged(AgentAvailabilityChangedEvent event) {
        if (event.available()) {
            chatQueue.run();
        }
    }
}
