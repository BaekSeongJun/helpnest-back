// @owner PMJ
package com.helpnest.domain.chat.dto;

import java.time.OffsetDateTime;

import com.helpnest.domain.chat.entity.ChatRoomStatus;

/** 채팅방 목록 한 줄 (GET /api/chat/rooms) — 고객은 상담원 이름, 상담원은 고객 이름을 본다 */
public record ChatRoomResponse(Long roomId, ChatRoomStatus status, Long ticketId, Long customerId,
        String customerName, String agentName, OffsetDateTime queuedAt, OffsetDateTime openedAt,
        OffsetDateTime closedAt) {
}
