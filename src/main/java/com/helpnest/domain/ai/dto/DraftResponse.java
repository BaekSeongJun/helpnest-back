// @owner SSJ
package com.helpnest.domain.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 초안 생성·목록 응답 (docs/04 §12) */
public record DraftResponse(Long draftId, String content, List<Reference> references, String model,
        OffsetDateTime createdAt) {

    /** type: FAQ | REPLY. label: FAQ 질문 / 과거 답변 앞 40자 */
    public record Reference(String type, Long id, String label) {
    }
}
