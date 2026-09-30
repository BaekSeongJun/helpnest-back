// @owner PMJ
package com.helpnest.domain.notification.port;

/**
 * 호출자: 백성준, 신수진 (docs/02 §5.2)
 *
 * <p>웹 알림의 유일한 입구다. 저장(NOTIFICATION 테이블)과 WebSocket 발송
 * ({@code /user/queue/notifications}, docs/02 §6)을 구현이 함께 처리하므로 호출자는 둘을
 * 구분할 필요가 없다.
 */
public interface NotificationPort {

    /**
     * 알림 한 건을 보낸다.
     *
     * @param receiverId 받는 회원의 member_id. 비회원은 웹 알림 대상이 아니며 메일로만 안내한다
     * @param type       docs/03 §2.1 notification.type 값 — ASSIGNED, SLA_WARNING, SLA_BREACHED,
     *                   CUSTOMER_REPLY, AGENT_REPLY, STATUS_CHANGED, UNASSIGNED, CHAT_REQUEST.
     *                   enum 이 아니라 String 인 이유는 호출자가 내 도메인 타입을 import 하지 않게
     *                   하려는 것이며, NOTIFICATION 테이블과 타입 enum 은 로드맵상 S2 다
     * @param ticketId   관련 티켓. 티켓과 무관한 알림이면 {@code null} 을 넘긴다(DDL 도 nullable)
     * @param message    알림 문구. NOTIFICATION.message 가 VARCHAR(300) 이므로 300자를 넘기면 안 된다
     */
    void notify(Long receiverId, String type, Long ticketId, String message);
}
