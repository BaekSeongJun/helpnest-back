// @owner SSJ
package com.helpnest.domain.ai.port;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// ponytail: S0 스텁 — AI-1 분류 서비스(S1)에서 TICKET_AI_RESULT.overridden_by 기록으로 교체
@Slf4j
@Component
public class AiResultAdapter implements AiResultPort {

    @Override
    public void markOverridden(Long ticketId, Long memberId, String category, String priority) {
        log.debug("[stub] markOverridden ticketId={} memberId={} category={} priority={}",
                ticketId, memberId, category, priority);
    }
}
