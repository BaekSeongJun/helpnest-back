// @owner PMJ
package com.helpnest.domain.notification.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.notification.entity.Notification;

/**
 * 알림 저장·조회. 호출자는 {@code NotificationAdapter}(저장)와 알림 조회 API(docs/04 §9)다.
 *
 * <p>조회 메서드 세 개가 모두 파생 쿼리가 아니라 {@code @Query} 인 이유는
 * {@code isRead} 때문이다. {@code findByReceiverIdAndIsReadFalse} 라고 쓰면 Spring Data 가
 * {@code Is} 를 비교 키워드로 떼어 {@code read} 프로퍼티를 찾을 여지가 있어, 메서드 이름이
 * 조용히 다른 뜻이 되는 위험을 피했다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 수신자의 알림 목록 (GET /api/notifications, 화면 CS-08).
     * 건수 제한은 호출자가 {@code Pageable} 로 넘긴다.
     *
     * <p>{@code unreadOnly} 를 쿼리 안의 분기로 둬 메서드를 하나로 합쳤다 — API 의
     * {@code ?unreadOnly=true} 파라미터와 1:1 로 대응하므로 호출자가 분기할 일이 없다.
     *
     * <p>정렬 기준이 createdAt 이 아니라 id 인 이유: created_at 은 같은 밀리초에 여러 건이
     * 들어가면 순서가 흔들린다(미배정 알림은 LEAD 전원에게 루프로 저장된다). BIGSERIAL 은
     * 삽입 순서를 그대로 보존하므로 동률이 없다.
     */
    @Query("""
            select n from Notification n
            where n.receiverId = :receiverId
              and (:unreadOnly = false or n.isRead = false)
            order by n.id desc
            """)
    List<Notification> findForReceiver(@Param("receiverId") Long receiverId,
            @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    /** 벨 배지에 찍는 미읽음 수 (GET /api/notifications/unread-count) */
    @Query("select count(n) from Notification n where n.receiverId = :receiverId and n.isRead = false")
    long countUnread(@Param("receiverId") Long receiverId);

    /**
     * 알림 한 건을 읽음으로 바꾼다 (PATCH /api/notifications/{id}/read).
     *
     * <p>조건에 {@code receiverId} 가 들어가는 것이 이 쿼리의 요점이다 — 소유 검증을 UPDATE
     * 조건으로 하면 "조회해서 주인을 확인한 뒤 수정"의 두 단계가 한 번으로 줄고, 그 사이에
     * 끼어들 여지도 없어진다. 남의 알림이면 영향 행이 0이고 호출자는 그것을 404 로 바꾼다
     * ({@code NotificationService} 주석).
     *
     * <p>{@code isRead = false} 를 조건에 넣지 않아 이미 읽은 알림에도 영향 행 1을 돌려준다 —
     * 멱등이어야 하는 이유는 서비스 쪽에 적어 두었다.
     *
     * @return 영향 행 수 (0 = 없거나 남의 알림, 1 = 처리됨)
     */
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.isRead = true where n.id = :id and n.receiverId = :receiverId")
    int markRead(@Param("receiverId") Long receiverId, @Param("id") Long id);

    /**
     * 수신자의 미읽음 알림을 모두 읽음으로 바꾼다 (PATCH /api/notifications/read-all).
     *
     * <p>엔티티를 하나씩 불러 {@link Notification#markRead()} 를 호출하지 않고 벌크 UPDATE 를
     * 쓴다 — "모두 읽음"은 수백 건이 될 수 있고 하나하나 적재할 이유가 없다.
     *
     * <p>{@code clearAutomatically} 로 영속성 컨텍스트를 비우는 이유: 벌크 UPDATE 는 DB 만
     * 바꾸므로 이미 적재된 엔티티는 isRead=false 로 남는다. 같은 트랜잭션에서 되읽으면 바뀌지
     * 않은 값을 보게 되는데, 이 함정에 가장 먼저 걸리는 것은 테스트다.
     *
     * @return 바뀐 건수
     */
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.isRead = true where n.receiverId = :receiverId and n.isRead = false")
    int markAllRead(@Param("receiverId") Long receiverId);
}
