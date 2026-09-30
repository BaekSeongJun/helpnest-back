// @owner SSJ
package com.helpnest.domain.ai.port;

/** 호출자: 박민재(상담원 수동 분류 수정) (docs/02 §5.2, docs/05 §3.3) */
public interface AiResultPort {

    /** 상담원이 분류를 수정했음을 기록 (overridden_by) */
    void markOverridden(Long ticketId, Long memberId, String category, String priority);
}
