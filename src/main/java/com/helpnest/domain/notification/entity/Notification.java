// @owner PMJ
package com.helpnest.domain.notification.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 웹 알림 한 건. DDL 은 docs/03 §3.2 가 단일 권위다.
 *
 * <h2>receiver_id / ticket_id 를 연관관계로 두지 않는 이유</h2>
 * {@code receiverId} 는 백성준의 MEMBER 를 가리키므로 엔티티 참조를 두면 다른 도메인 엔티티를
 * import 해야 한다(docs/10 §3.3 금지). {@code ticketId} 는 같은 소유자의 테이블이지만
 * 티켓 도메인 전체가 식별자 참조로 일관돼 있어({@code TicketHistory} 주석) 같은 방식을 쓴다.
 * 알림 목록 화면이 필요한 것은 티켓번호가 아니라 "클릭하면 갈 곳"인 ticketId 뿐이다.
 *
 * <p>created_at 만 있고 updated_at 이 없으므로 {@code BaseTimeEntity} 를 상속하지 않고
 * 필드를 직접 둔다 — {@code SlaPolicy}·{@code TicketHistory} 와 같은 이유다. 상속하면
 * ddl-auto=validate 가 updated_at 컬럼 부재로 기동을 막는다.
 */
@Getter
@Entity
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /** message 컬럼이 VARCHAR(300) 이다. 어댑터가 이 길이로 잘라 넣는다 */
    public static final int MESSAGE_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    /** 받는 회원의 member_id. 비회원은 웹 알림 대상이 아니라 메일로만 안내한다 */
    @Column(nullable = false)
    private Long receiverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    /** 관련 티켓. 티켓과 무관한 알림이면 NULL 이다(DDL 도 nullable) */
    private Long ticketId;

    @Column(nullable = false, length = MESSAGE_MAX_LENGTH)
    private String message;

    /** 읽음 여부. 필드명 isRead 가 암묵 네이밍 전략으로 is_read 컬럼에 매핑된다 */
    @Column(nullable = false)
    private boolean isRead;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private Notification(Long receiverId, NotificationType type, Long ticketId, String message) {
        this.receiverId = receiverId;
        this.type = type;
        this.ticketId = ticketId;
        this.message = message;
    }

    /**
     * 읽음 처리 (PATCH /api/notifications/{id}/read).
     *
     * <p>이미 읽은 알림을 다시 호출해도 그만이다 — 같은 알림을 두 번 클릭하거나 벨과 목록에서
     * 각각 누르는 일이 흔하므로 멱등이어야 한다. 그래서 가드 없이 그냥 true 로 둔다.
     */
    public void markRead() {
        this.isRead = true;
    }
}
