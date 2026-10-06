// @owner PMJ
package com.helpnest.domain.notification.dto;

import java.time.OffsetDateTime;

import com.helpnest.domain.notification.entity.Notification;

/**
 * {@code /user/queue/notifications} 로 내려보내는 알림 한 건 (docs/04 §11).
 *
 * <p>필드가 docs/04 §11 의 {@code {notificationId, type, ticketId, message, createdAt}} 과
 * 1:1 이다. 알림 조회 REST API(docs/04 §9) 도 같은 모양을 쓰면 프론트가 <b>푸시로 받은 것과
 * 조회로 받은 것을 같은 타입으로 다룰 수 있으므로</b> 응답 DTO 를 따로 만들지 않고 이것을
 * 재사용한다.
 *
 * <p>{@code isRead} 를 담지 않는다 — 푸시로 오는 알림은 정의상 미읽음이다. 목록 조회에서
 * 읽음 여부가 필요해지면 그때 추가한다.
 */
public record NotificationPayload(
        Long notificationId,
        String type,
        Long ticketId,
        String message,
        OffsetDateTime createdAt) {

    public static NotificationPayload from(Notification notification) {
        return new NotificationPayload(
                notification.getId(),
                notification.getType().name(),
                notification.getTicketId(),
                notification.getMessage(),
                notification.getCreatedAt());
    }
}
