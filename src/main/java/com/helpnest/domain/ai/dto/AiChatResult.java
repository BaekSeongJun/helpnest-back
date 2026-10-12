// @owner SSJ
package com.helpnest.domain.ai.dto;

/** AI 상담 LLM 응답 (docs/05 §7.2). answer 가 비면 FAQ 로 답할 수 없다는 뜻 → handoff */
public record AiChatResult(String answer) {
}
