// @owner PMJ
package com.helpnest.domain.notification.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.notification.dto.NotificationPayload;
import com.helpnest.domain.notification.service.NotificationService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;

import lombok.RequiredArgsConstructor;

/**
 * 알림 조회·읽음 API (docs/04 §9, 화면 CS-08 Header 드롭다운).
 *
 * <h2>수신자는 항상 토큰에서 온다</h2>
 * 네 엔드포인트 모두 수신자를 파라미터로 받지 않고 {@link JwtProvider#memberId(Jwt)} 로만
 * 얻는다. {@code receiverId} 를 쿼리 파라미터로 받으면 남의 알림을 읽는 길이 열리고, 그것을
 * 막는 검증을 네 곳에 중복으로 넣어야 한다.
 *
 * <h2>Guest 토큰이 403 이 되는 경로</h2>
 * {@code /api/notifications/**} 는 {@code SecurityConfig} 의 역할 prefix 에 없어
 * {@code anyRequest().authenticated()} 로 떨어지고, <b>Guest 토큰도 "인증됨"이라 필터는
 * 통과한다</b>. 막는 것은 {@link JwtProvider#memberId(Jwt)} 다 — Guest 토큰이면 FORBIDDEN 을
 * 던진다. 비회원은 member 행이 없고 {@code notification.receiver_id} 가 member FK NOT NULL
 * 이므로 받을 알림 자체가 존재할 수 없다.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 최근 알림 목록 (최신순 20건 상한).
     *
     * <p>페이징이 없는 것은 빠뜨린 것이 아니다 — CS-08 은 드롭다운에 "최근 20개"를 그리고
     * [모두 읽음]으로 끝난다. 더 보기 화면이 생기면 그때 {@code Pageable} 을 노출한다.
     *
     * @param unreadOnly true 면 읽은 알림을 제외한다. 기본 false (전체)
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationPayload>>> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                notificationService.findRecent(JwtProvider.memberId(jwt), unreadOnly)));
    }

    /** 벨 배지에 찍는 미읽음 수 */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                new UnreadCountResponse(notificationService.countUnread(JwtProvider.memberId(jwt)))));
    }

    /**
     * 알림 한 건 읽음 처리. 내 알림이 아니거나 없으면 <b>403 이 아니라 404</b> 다 —
     * 403 은 그 id 의 알림이 존재한다는 사실을 노출한다({@link NotificationService} 주석).
     */
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> read(
            @PathVariable Long notificationId,
            @AuthenticationPrincipal Jwt jwt) {
        notificationService.markRead(JwtProvider.memberId(jwt), notificationId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /** 모두 읽음. 바꿀 것이 없어도 200 이다 */
    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<ReadAllResponse>> readAll(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResponse.ok(
                new ReadAllResponse(notificationService.markAllRead(JwtProvider.memberId(jwt)))));
    }

    /**
     * 숫자 하나를 객체로 감싸는 이유: {@code ApiResponse.data} 가 {@code 3} 같은 생값이면
     * 나중에 필드를 하나 더 붙일 때 프론트의 파싱이 깨진다. 응답 형태를 넓히는 비용이 0 이 되게
     * 처음부터 객체로 둔다.
     */
    public record UnreadCountResponse(long unreadCount) {
    }

    /** 바뀐 건수. 프론트가 "n개를 읽음으로 표시했습니다" 를 띄울 수 있게 돌려준다 */
    public record ReadAllResponse(int updated) {
    }
}
