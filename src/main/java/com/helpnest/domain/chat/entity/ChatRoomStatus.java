// @owner PMJ
package com.helpnest.domain.chat.entity;

/**
 * 채팅방 상태. 값 집합의 단일 권위는 docs/03 §2.1 이다.
 *
 * <pre>
 * WAITING ─┬─ open ────→ OPEN ── close → CLOSED
 *          ├─ convert ─→ CONVERTED  (5분 초과 후 "문의로 남기기")
 *          └─ cancel ──→ CANCELED   (대기 중 나가기)
 * </pre>
 * 전이 검증은 {@link ChatRoom} 의 변경 메서드가 직접 한다.
 */
public enum ChatRoomStatus {

    /** 상담원 배정 대기. ticket_id 가 아직 없다. */
    WAITING,

    /** 상담원 연결됨. CHAT 티켓이 함께 생성돼 있다. */
    OPEN,

    /** 상담 종료. */
    CLOSED,

    /** 대기 중 일반 문의 티켓으로 전환됨(FR-CHT-05). */
    CONVERTED,

    /** 대기 중 고객이 나감. 티켓을 만들지 않는다. */
    CANCELED
}
