// @owner PMJ
package com.helpnest.domain.chat.dto;

import java.time.OffsetDateTime;

/**
 * 채팅방 상태. {@code POST /api/chat/rooms} 응답과 {@code /user/queue/chat-status} 푸시가 같은
 * 모양을 쓴다(docs/04 §10·§11) — 프론트가 응답과 푸시를 같은 함수로 처리할 수 있게.
 *
 * @param status   WAITING | OPEN | TIMEOUT | CLOSED | CONVERTED | CANCELED.
 *                 TIMEOUT 은 저장 상태가 아니라 "WAITING 이면서 5분이 지났음"을 뜻한다(FR-CHT-05)
 * @param position 대기 순번(1부터). WAITING·TIMEOUT 이 아니면 null
 * @param queuedAt 대기 시작 시각. 화면의 경과 시간 표시 기준
 * @param agentName 연결된 상담원 이름. OPEN 전이면 null
 */
public record ChatStatusPayload(Long roomId, String status, Integer position, OffsetDateTime queuedAt,
        String agentName) {
}
