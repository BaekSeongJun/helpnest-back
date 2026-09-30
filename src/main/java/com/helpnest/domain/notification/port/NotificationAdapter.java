// @owner PMJ
package com.helpnest.domain.notification.port;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * S0 스텁 — 실제 저장·발송은 <b>S2</b> 에서 구현한다. NOTIFICATION 테이블과 WebSocket 설정
 * (application-ws.yml)이 모두 로드맵 S2 항목이기 때문이다.
 *
 * <p><b>다른 스텁보다 유실 기간이 길다.</b> 백성준·신수진이 S1 에 이 포트를 호출해도 컴파일·실행은
 * 되지만 알림은 S2 까지 남지 않는다. void 라 호출자가 알 방법이 없으므로 info 로 남기고 메시지에
 * S2 구현 예정임을 박아 둔다.
 *
 * <p>TODO(PMJ) S2 구현 —
 * <ol>
 *   <li>NOTIFICATION 마이그레이션(docs/03 §3.2)과 엔티티·Repository 추가</li>
 *   <li>type 을 NotificationType enum 으로 변환하며 검증(docs/03 §2.1 의 8종).
 *       포트 시그니처는 String 을 유지한다 — 호출자가 내 enum 을 import 하지 않게 하려는 것이다</li>
 *   <li>저장 후 SimpMessagingTemplate 으로 {@code /user/queue/notifications} 발송(docs/02 §6)</li>
 *   <li>message 가 300자를 넘으면 잘라 저장한다(DDL VARCHAR(300))</li>
 * </ol>
 */
@Slf4j
@Component
public class NotificationAdapter implements NotificationPort {

    @Override
    public void notify(Long receiverId, String type, Long ticketId, String message) {
        log.info("[stub] notify receiverId={} type={} ticketId={} — S2 구현 예정, 알림이 저장·발송되지 않음",
                receiverId, type, ticketId);
    }
}
