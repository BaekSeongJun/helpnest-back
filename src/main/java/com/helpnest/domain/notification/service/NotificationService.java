// @owner PMJ
package com.helpnest.domain.notification.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.notification.dto.NotificationPayload;
import com.helpnest.domain.notification.repository.NotificationRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 알림 조회·읽음 처리 (docs/04 §9, 화면 CS-08).
 *
 * <h2>남의 알림은 "없는 것"으로 응답한다</h2>
 * 조회·읽음 모두 조건에 {@code receiver_id = 인증 memberId} 가 들어간다. 남의 알림 id 를
 * 넘기면 403 이 아니라 <b>404</b> 다 — 403 은 "그 id 의 알림이 존재한다"는 사실을 알려 주어
 * id 를 훑으며 남의 알림 개수를 세는 수단이 된다. {@code TicketGuestPort} 가 존재 여부를
 * 숨기려 예외 대신 null 을 쓰는 것과 같은 원칙이다.
 *
 * <p>그래서 읽음 처리는 <b>조회 후 소유자 검증</b>이 아니라 UPDATE 조건에 수신자를 넣어
 * 한 번에 끝낸다. 두 번 나가면 쿼리가 늘기도 하지만, 조회와 수정 사이에 끼어들 여지가 생기고
 * "존재하지만 남의 것"과 "아예 없음"을 코드가 구분하게 되어 실수로 403 을 돌려줄 길이 열린다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    /**
     * CS-08 이 드롭다운에 보여 주는 건수. 페이징을 두지 않는 이유는 화면이 "최근 20개 + 모두
     * 읽음"으로 끝나기 때문이다 — 더 보기가 필요해지면 그때 {@code Pageable} 을 노출한다.
     */
    private static final int RECENT_LIMIT = 20;

    private final NotificationRepository notificationRepository;

    /** 최신순 {@value #RECENT_LIMIT} 건. {@code unreadOnly} 면 읽은 알림을 제외한다 */
    public List<NotificationPayload> findRecent(Long receiverId, boolean unreadOnly) {
        return notificationRepository.findForReceiver(receiverId, unreadOnly, PageRequest.of(0, RECENT_LIMIT))
                .stream()
                .map(NotificationPayload::from)
                .toList();
    }

    /** 벨 배지 숫자 */
    public long countUnread(Long receiverId) {
        return notificationRepository.countUnread(receiverId);
    }

    /**
     * 알림 한 건을 읽음으로 바꾼다. 영향 행이 0이면 내 알림이 아니거나 없는 것이므로 404 다.
     *
     * <p>UPDATE 조건에 {@code isRead = false} 를 <b>넣지 않는다.</b> 넣으면 이미 읽은 내 알림도
     * 영향 행 0이 되어 404 가 되는데, 벨과 목록에서 같은 알림을 각각 누르는 일이 흔하므로
     * 두 번째 클릭마다 에러를 보게 된다. 조건 없이 true 로 덮어쓰면 이미 읽은 행도 영향 행 1을
     * 돌려주므로 <b>쿼리 한 번으로 멱등 + 소유 검증 + 404 판정</b>이 모두 끝난다.
     *
     * @throws BusinessException 내 알림이 아니거나 없으면 {@link CommonErrorCode#NOT_FOUND}
     */
    @Transactional
    public void markRead(Long receiverId, Long notificationId) {
        if (notificationRepository.markRead(receiverId, notificationId) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    /**
     * 수신자의 미읽음 알림을 모두 읽음으로 바꾼다. 바꿀 것이 없어도 성공이다 — "모두 읽음"의
     * 결과는 어느 쪽이든 "미읽음 0" 이고, 없는 것을 에러로 알릴 이유가 없다.
     *
     * @return 바뀐 건수
     */
    @Transactional
    public int markAllRead(Long receiverId) {
        return notificationRepository.markAllRead(receiverId);
    }
}
