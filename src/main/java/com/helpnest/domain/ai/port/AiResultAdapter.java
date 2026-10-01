// @owner SSJ
package com.helpnest.domain.ai.port;

import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiResultAdapter implements AiResultPort {

    private final TicketAiResultRepository repository;

    /** 수정된 category·priority 는 티켓에 이미 반영돼 있어 여기서는 overridden_by 만 기록한다 */
    @Override
    @Transactional
    public void markOverridden(Long ticketId, Long memberId, String category, String priority) {
        repository.findByTicketId(ticketId).ifPresentOrElse(
                result -> result.markOverridden(memberId),
                // 분류 전(또는 결과 없음)에 상담원이 먼저 수정한 경우 — 기록할 AI 결과가 없다
                () -> log.info("AI 결과 없음, overridden 기록 생략 ticketId={}", ticketId));
    }
}
