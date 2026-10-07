// @owner PMJ
package com.helpnest.domain.chat.dto;

/** STOMP {@code /app/chat/{roomId}/send} 본문. 길이 검증은 서비스가 한다(STOMP 경로라 @Valid 가 없다) */
public record ChatSendRequest(String content) {
}
