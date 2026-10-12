// @owner SSJ
package com.helpnest.domain.ai.dto;

import java.util.List;

/** AI 상담 응답 (docs/04 §12). handoff=true 면 화면이 [상담원 연결] 을 강조한다 */
public record AiChatResponse(String answer, List<Reference> references, boolean handoff) {

    public record Reference(Long faqId, String question) {
    }
}
