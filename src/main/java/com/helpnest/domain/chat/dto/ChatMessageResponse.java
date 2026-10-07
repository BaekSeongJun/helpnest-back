// @owner PMJ
package com.helpnest.domain.chat.dto;

import java.time.OffsetDateTime;

/** 채팅 메시지 — 이전 메시지 조회 응답과 {@code /topic/chat/{roomId}} 푸시가 같은 모양이다 (docs/04 §11) */
public record ChatMessageResponse(Long messageId, Long senderId, String senderName, String content,
        OffsetDateTime createdAt) {
}
