// @owner PMJ
package com.helpnest.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.notification.entity.Notification;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.repository.NotificationRepository;
import com.helpnest.global.security.JwtProvider;

/**
 * 알림 조회·읽음 API 통합 테스트 (docs/04 §9, 화면 CS-08).
 *
 * <p>알림은 포트가 아니라 Repository 로 직접 심는다. 검증 대상은 "누가 무엇을 볼 수 있고
 * 바꿀 수 있는가"이고, 포트를 거치면 type 검증·절단·발송까지 섞여 실패 원인이 흐려진다.
 * 저장 쪽 규칙은 {@code NotificationAdapterTest} 가 따로 본다.
 *
 * <p>{@code @MockitoBean} 을 쓰지 않아 다른 {@code @SpringBootTest} 와 컨텍스트를 공유한다 —
 * 컨텍스트가 늘면 Hikari 풀도 같이 늘어 PostgreSQL {@code max_connections} 를 넘긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("/api/notifications — 조회·읽음 처리")
class NotificationApiTest {

    /** CS-08 드롭다운 상한. NotificationService.RECENT_LIMIT 과 같아야 한다 */
    private static final int RECENT_LIMIT = 20;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    JwtProvider jwtProvider;

    private Long receiverId;
    private Long otherId;
    private String token;
    private String otherToken;

    @BeforeEach
    void setUp() {
        receiverId = memberId("agent1@helpnest.local");
        otherId = memberId("agent2@helpnest.local");
        token = jwtProvider.createAccessToken(receiverId, "AGENT");
        otherToken = jwtProvider.createAccessToken(otherId, "AGENT");
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    private Notification given(Long owner, String message) {
        return notificationRepository.save(Notification.builder()
                .receiverId(owner)
                .type(NotificationType.ASSIGNED)
                .ticketId(null)
                .message(message)
                .build());
    }

    @Nested
    @DisplayName("GET /api/notifications")
    class List {

        @Test
        @DisplayName("최신순으로 주고 내 알림만 보인다")
        void newestFirstAndOnlyMine() throws Exception {
            given(receiverId, "첫 번째");
            given(receiverId, "두 번째");
            given(otherId, "남의 알림");

            mockMvc.perform(get("/api/notifications")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].message").value("두 번째"))
                    .andExpect(jsonPath("$.data[1].message").value("첫 번째"));
        }

        @Test
        @DisplayName("최근 20건 상한 — 21건을 넣어도 20건만, 가장 최신이 맨 앞")
        void capsAtRecentLimit() throws Exception {
            for (int i = 1; i <= RECENT_LIMIT + 1; i++) {
                given(receiverId, "알림 " + i);
            }

            mockMvc.perform(get("/api/notifications")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(RECENT_LIMIT))
                    .andExpect(jsonPath("$.data[0].message").value("알림 " + (RECENT_LIMIT + 1)));
        }

        @Test
        @DisplayName("unreadOnly=true 는 읽은 알림을 제외한다")
        void unreadOnlyExcludesRead() throws Exception {
            Notification read = given(receiverId, "읽은 것");
            given(receiverId, "안 읽은 것");
            notificationRepository.markRead(receiverId, read.getId());

            mockMvc.perform(get("/api/notifications").param("unreadOnly", "true")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].message").value("안 읽은 것"));
        }

        @Test
        @DisplayName("응답 필드가 STOMP payload 와 같다 — 프론트가 같은 타입으로 다룬다(docs/04 §11)")
        void payloadShapeMatchesStomp() throws Exception {
            Notification saved = given(receiverId, "새 티켓이 배정되었습니다.");

            mockMvc.perform(get("/api/notifications")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(jsonPath("$.data[0].notificationId").value(saved.getId()))
                    .andExpect(jsonPath("$.data[0].type").value("ASSIGNED"))
                    .andExpect(jsonPath("$.data[0].ticketId").doesNotExist())
                    .andExpect(jsonPath("$.data[0].message").value("새 티켓이 배정되었습니다."))
                    .andExpect(jsonPath("$.data[0].createdAt").exists());
        }
    }

    @Nested
    @DisplayName("PATCH /{id}/read")
    class MarkRead {

        /**
         * 404 이지 403 이 아니다. 403 은 "그 id 의 알림이 존재한다"를 알려 주어 id 를 훑으며
         * 남의 알림 개수를 세는 수단이 된다.
         */
        @Test
        @DisplayName("남의 알림 id 는 404 이고 그 행은 바뀌지 않는다")
        void othersNotificationIsNotFound() throws Exception {
            Notification others = given(otherId, "남의 알림");

            mockMvc.perform(patch("/api/notifications/{id}/read", others.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isNotFound());

            assertThat(notificationRepository.findById(others.getId()).orElseThrow().isRead()).isFalse();
            assertThat(notificationRepository.countUnread(otherId)).isEqualTo(1);
        }

        @Test
        @DisplayName("없는 id 도 404")
        void unknownIdIsNotFound() throws Exception {
            mockMvc.perform(patch("/api/notifications/{id}/read", -1L)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("내 알림은 읽음이 되고, 두 번 눌러도 200 이다 — 멱등")
        void marksMineAndIsIdempotent() throws Exception {
            Notification mine = given(receiverId, "내 알림");

            mockMvc.perform(patch("/api/notifications/{id}/read", mine.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
            assertThat(notificationRepository.countUnread(receiverId)).isZero();

            mockMvc.perform(patch("/api/notifications/{id}/read", mine.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("미읽음 수와 모두 읽음")
    class UnreadAndReadAll {

        @Test
        @DisplayName("read-all 후 unread-count 가 0 이고 남의 알림은 그대로다")
        void readAllThenZero() throws Exception {
            given(receiverId, "하나");
            given(receiverId, "둘");
            given(otherId, "남의 알림");

            mockMvc.perform(get("/api/notifications/unread-count")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(jsonPath("$.data.unreadCount").value(2));

            mockMvc.perform(patch("/api/notifications/read-all")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.updated").value(2));

            mockMvc.perform(get("/api/notifications/unread-count")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(jsonPath("$.data.unreadCount").value(0));
            mockMvc.perform(get("/api/notifications/unread-count")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                    .andExpect(jsonPath("$.data.unreadCount").value(1));
        }

        @Test
        @DisplayName("바꿀 것이 없어도 read-all 은 200 / updated=0")
        void readAllWithNothingToDo() throws Exception {
            mockMvc.perform(patch("/api/notifications/read-all")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.updated").value(0));
        }
    }

    /**
     * {@code /api/notifications/**} 는 SecurityConfig 의 역할 prefix 에 없어
     * {@code anyRequest().authenticated()} 로 떨어지고 Guest 토큰도 필터는 통과한다.
     * 막는 것은 {@code JwtProvider.memberId(jwt)} 이며, 이 테스트가 그 사실을 고정한다 —
     * 컨트롤러에서 memberId 추출을 빼고 파라미터로 받게 바꾸면 여기서 깨진다.
     */
    @Nested
    @DisplayName("비회원·비인증 접근")
    class Access {

        @Test
        @DisplayName("Guest 토큰은 403 — 비회원은 알림 대상이 아니다")
        void guestTokenForbidden() throws Exception {
            String guestToken = jwtProvider.createGuestToken(1L);

            mockMvc.perform(get("/api/notifications")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/notifications/unread-count")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/notifications/read-all")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + guestToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("토큰이 없으면 401")
        void noTokenUnauthorized() throws Exception {
            mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        }
    }
}
