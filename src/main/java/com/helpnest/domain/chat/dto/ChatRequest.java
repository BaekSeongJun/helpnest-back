// @owner PMJ
package com.helpnest.domain.chat.dto;

import jakarta.validation.constraints.Size;

/**
 * 채팅 요청 (POST /api/chat/rooms) 선택 본문.
 *
 * @param aiTranscript 상담원 연결 전 AI 상담 내용(FR-CHT-07). 새 방의 고객 첫 메시지로 남긴다
 */
public record ChatRequest(
        @Size(max = 2_000, message = "AI 상담 내용은 2,000자 이하여야 합니다.")
        String aiTranscript) {
}
