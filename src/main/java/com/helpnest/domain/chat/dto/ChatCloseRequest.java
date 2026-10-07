// @owner PMJ
package com.helpnest.domain.chat.dto;

/**
 * 채팅 종료 (PATCH /api/chat/rooms/{roomId}/close).
 *
 * @param resolve true 면 티켓도 RESOLVED 로 바꾼다(FR-CHT-03). 본문·필드가 없으면 false 로 본다 —
 *                후속 처리가 남은 상담은 채팅만 닫고 티켓은 IN_PROGRESS 로 둔다.
 *                {@code boolean} 이 아닌 이유: Jackson 3 는 primitive 필드가 빠진 {@code {}} 를 400 으로 거부한다
 */
public record ChatCloseRequest(Boolean resolve) {
}
