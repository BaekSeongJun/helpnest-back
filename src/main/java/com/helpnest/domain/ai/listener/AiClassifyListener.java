// @owner SSJ
package com.helpnest.domain.ai.listener;

import com.helpnest.domain.ai.service.ClassifyService;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수 커밋 후 AI-1 분류 (docs/02 §5.1, docs/05 §3.1 1번). 스레드 풀은 global/config/AsyncConfig
 */
@Component
@RequiredArgsConstructor
public class AiClassifyListener {

    private final ClassifyService classifyService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TicketCreatedEvent event) {
        classifyService.classify(event.ticketId());
    }
}
