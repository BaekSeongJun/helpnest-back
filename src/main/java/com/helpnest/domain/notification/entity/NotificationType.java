// @owner PMJ
package com.helpnest.domain.notification.entity;

/**
 * NOTIFICATION.type — 웹 알림의 종류. 단일 권위는 docs/03 §2.1 이다.
 *
 * <p><b>이 enum 을 다른 도메인에서 import 하지 않는다.</b> 호출자가 쓰는
 * {@code NotificationPort.notify} 의 type 파라미터는 의도적으로 {@code String} 이며
 * 변환·검증은 {@code NotificationAdapter} 안에서만 한다
 * ({@link com.helpnest.domain.notification.port.NotificationPort} 주석).
 */
public enum NotificationType {

    /** 티켓이 나에게 배정됨. 수신자는 담당 상담원 (FR-ASN-01) */
    ASSIGNED,

    /** 첫 응답 기한 임박(경과 비율 도달). 수신자는 담당 상담원 (PRD 6.1) */
    SLA_WARNING,

    /** 첫 응답 기한 초과. 수신자는 담당 상담원과 팀장 (PRD 6.1) */
    SLA_BREACHED,

    /** 고객이 추가 답글을 남김. 수신자는 담당 상담원 */
    CUSTOMER_REPLY,

    /** 상담원이 공개 답변을 남김. <b>수신자는 회원 고객</b>이며 내부 메모는 대상이 아니다 */
    AGENT_REPLY,

    /** 티켓 상태가 바뀜 */
    STATUS_CHANGED,

    /** 가용 상담원이 없어 미배정으로 남음. 수신자는 LEAD·ADMIN (FR-ASN-01) */
    UNASSIGNED,

    /** 채팅 상담 요청이 대기열에 들어옴. 수신자는 상담원 (S3) */
    CHAT_REQUEST
}
