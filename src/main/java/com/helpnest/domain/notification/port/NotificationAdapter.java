// @owner PMJ
package com.helpnest.domain.notification.port;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.notification.entity.Notification;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.repository.NotificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 웹 알림 저장. 호출자는 백성준·신수진이며 포트 하나로 들어온다(docs/02 §5.2).
 *
 * <p>S0~S1 동안 이 어댑터는 로그만 남기는 스텁이었다. 이제 저장이 실구현됐으므로
 * {@code AssignmentService} 의 UNASSIGNED 알림 같은 기존 호출은 <b>호출처 수정 없이</b>
 * 그대로 notification 행을 남긴다.
 *
 * <p>TODO(PMJ) 발송만 남음 — 저장 후 {@code SimpMessagingTemplate} 으로
 * {@code /user/queue/notifications} 에 보내는 한 줄을 {@link #notify} 끝에 추가한다
 * (docs/02 §6). WebSocket 설정(STOMP 엔드포인트·JWT 인터셉터)이 선행이라 별도 태스크다.
 * 저장이 이미 되고 있으므로 발송이 늦어도 알림은 유실되지 않고, 벨을 새로 열면 보인다.
 *
 * <h2>호출자에게 예외를 올리지 않는다</h2>
 * 반환형이 void 라 호출자는 실패를 알 방법이 없고, 애초에 알림 실패로 배정·답변 같은 업무가
 * 되돌려지면 안 된다. 그래서 세 가지 입력 오류(수신자 없음 / 알 수 없는 type / 빈 문구)는
 * 저장하지 않고 경고 로그만 남기고 돌아간다.
 *
 * <p><b>사전 검증을 통과한 뒤의 DB 오류는 호출자 트랜잭션까지 되돌린다.</b> 이 메서드는
 * 호출자의 트랜잭션에 참여하므로(REQUIRED), FK 위반 같은 오류가 나면 PostgreSQL 트랜잭션이
 * 이미 abort 상태가 되어 아래 catch 로 삼켜도 호출자의 commit 이
 * {@code UnexpectedRollbackException} 으로 실패한다. <b>catch 는 입력 오류용이지 DB 오류용이
 * 아니다.</b>
 *
 * <p>그래도 REQUIRED 를 쓰는 이유는 {@code REQUIRES_NEW} 의 대가가 더 크기 때문이다 — 알림마다
 * 커넥션을 하나 더 점유하고, 업무 트랜잭션이 롤백돼도 알림은 남아 "배정 실패했는데 알림은 갔다"가
 * 된다. 남는 실패 모드(없는 member_id·ticket_id)는 전부 호출자의 버그이며, 그때는 조용히
 * 넘기는 것보다 호출자가 함께 실패해 드러나는 쪽이 낫다. 따라서 <b>호출자는 자기가 다루고 있는
 * 실제 식별자만 넘겨야 한다</b> — 아직 저장되지 않은 티켓의 id 를 추측해 넘기면 안 된다.
 *
 * <p>ponytail: 알림 실패를 완전히 격리해야 할 만큼 호출처가 늘면 {@code REQUIRES_NEW} 로 바꾸고
 * 알림을 커밋 이후 발행({@code @TransactionalEventListener(AFTER_COMMIT)})으로 옮긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
public class NotificationAdapter implements NotificationPort {

    private final NotificationRepository notificationRepository;

    /**
     * {@inheritDoc}
     *
     * <p>{@code type} 이 {@link NotificationType} 이 아니라 String 인 것은 설계이며
     * <b>enum 으로 '개선'하지 않는다</b>. 호출자(백성준·신수진)가 내 도메인 enum 을 import 하게
     * 되면 docs/10 §3.3 의 타 도메인 import 금지에 걸리고, 알림 종류를 추가할 때마다 남의
     * 파일이 컴파일 대상에 묶인다. 변환·검증 책임은 여기 한 곳에 모은다.
     */
    @Override
    public void notify(Long receiverId, String type, Long ticketId, String message) {
        if (receiverId == null) {
            log.warn("[noti] 수신자가 없어 알림을 버린다 type={} ticketId={}", type, ticketId);
            return;
        }
        if (message == null || message.isBlank()) {
            log.warn("[noti] 문구가 비어 알림을 버린다 receiverId={} type={}", receiverId, type);
            return;
        }
        NotificationType parsed = parseType(type, receiverId);
        if (parsed == null) {
            return;
        }

        try {
            Notification saved = notificationRepository.save(Notification.builder()
                    .receiverId(receiverId)
                    .type(parsed)
                    .ticketId(ticketId)
                    .message(truncate(message))
                    .build());
            log.debug("[noti] 저장 notificationId={} receiverId={} type={} ticketId={}",
                    saved.getId(), receiverId, parsed, ticketId);
        } catch (RuntimeException e) {
            // 알림 문구는 고객 정보를 담을 수 있어 로그에 남기지 않는다(docs/10 §3.3).
            // 여기서 삼켜도 호출자 트랜잭션은 이미 롤백 표시가 됐다(클래스 주석) — 로그는
            // "알림 때문에 업무가 실패했다"를 추적할 단서로만 남긴다.
            log.error("[noti] 저장 실패 receiverId={} type={} ticketId={}", receiverId, parsed, ticketId, e);
        }
    }

    /**
     * docs/03 §2.1 의 8종에 없는 값이면 null 을 준다. 호출자가 포트의 Javadoc 에 적힌 값을
     * 잘못 적었다는 뜻이므로 조용히 넘기지 않고 warn 으로 남긴다 — 알림이 한 종류만 안 오는
     * 버그는 로그 없이는 찾기 어렵다.
     */
    private NotificationType parseType(String type, Long receiverId) {
        try {
            return NotificationType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("[noti] 알 수 없는 type={} 이라 알림을 버린다 receiverId={}", type, receiverId);
            return null;
        }
    }

    /**
     * message 컬럼이 VARCHAR(300) 이라 넘치면 자른다. 저장 자체를 포기하지 않는 이유는,
     * 문구가 길다는 것이 알림을 받을 이유가 사라졌다는 뜻은 아니기 때문이다 — 뒤가 잘려도
     * 벨에서 티켓으로 넘어가면 전체 내용을 볼 수 있다.
     */
    private String truncate(String message) {
        return message.length() <= Notification.MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, Notification.MESSAGE_MAX_LENGTH);
    }
}
