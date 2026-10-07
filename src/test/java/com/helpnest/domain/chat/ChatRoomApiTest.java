// @owner PMJ
package com.helpnest.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.chat.entity.ChatMessage;
import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;
import com.helpnest.domain.chat.repository.ChatMessageRepository;
import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.domain.chat.scheduler.ChatQueueScheduler;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.notification.repository.NotificationRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.security.JwtProvider;

/**
 * 채팅 요청·대기열·전환·나가기 통합 테스트 (FR-CHT-01·04·05, docs/04 §10).
 *
 * <p>테스트 트랜잭션 안에서 돌기 때문에 {@code tryMatch} 의 "별도 트랜잭션" 분리는 여기서
 * 드러나지 않는다(테스트 트랜잭션에 합류). 검증 대상은 상태·티켓·순번 결과다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("/api/chat/rooms — 채팅 요청·대기열")
class ChatRoomApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    ChatRoomRepository roomRepository;
    @Autowired
    ChatMessageRepository messageRepository;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    ChatQueueScheduler scheduler;

    private Long customer1;
    private Long customer2;
    private String customer1Token;
    private String customer2Token;

    @BeforeEach
    void setUp() {
        customer1 = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        customer2 = memberRepository.findByEmail("customer2@helpnest.local").orElseThrow().getId();
        customer1Token = jwtProvider.createAccessToken(customer1, "CUSTOMER");
        customer2Token = jwtProvider.createAccessToken(customer2, "CUSTOMER");
    }

    /** 시드 상담원 전원의 상담 가능 여부. 테스트 트랜잭션이 롤백하므로 원복할 필요가 없다 */
    private void agentsAvailable(boolean available) {
        memberRepository.findAll().stream()
                .filter(m -> m.getRole() == MemberRole.AGENT)
                .forEach(m -> m.changeAvailable(available));
        memberRepository.flush();
    }

    private ResultActions requestChat(String token) throws Exception {
        return mockMvc.perform(post("/api/chat/rooms").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ChatRoom roomOf(Long customerId) {
        return roomRepository.findByCustomerIdOrderByIdDesc(customerId).getFirst();
    }

    @Nested
    @DisplayName("POST /api/chat/rooms")
    class Request {

        @Test
        @DisplayName("상담원이 있으면 즉시 OPEN + channel=CHAT, ASSIGNED 티켓이 생긴다")
        void 즉시_연결() throws Exception {
            agentsAvailable(true);

            requestChat(customer1Token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andExpect(jsonPath("$.data.agentName").isNotEmpty());

            ChatRoom room = roomOf(customer1);
            Ticket ticket = ticketRepository.findById(room.getTicketId()).orElseThrow();
            assertThat(ticket.getChannel()).isEqualTo(TicketChannel.CHAT);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
            assertThat(ticket.getAgentId()).isEqualTo(room.getAgentId());
        }

        @Test
        @DisplayName("상담원이 없으면 WAITING 순번 1·2, 티켓·팀장 UNASSIGNED 알림 없음")
        void 대기열() throws Exception {
            agentsAvailable(false);
            long ticketsBefore = ticketRepository.count();
            long notisBefore = notificationRepository.count();

            requestChat(customer1Token).andExpect(jsonPath("$.data.status").value("WAITING"));
            requestChat(customer2Token)
                    .andExpect(jsonPath("$.data.status").value("WAITING"))
                    .andExpect(jsonPath("$.data.position").value(2));

            assertThat(ticketRepository.count()).isEqualTo(ticketsBefore);
            assertThat(notificationRepository.count()).isEqualTo(notisBefore);
        }

        @Test
        @DisplayName("진행 중 방이 있으면 다시 요청해도 같은 방이다")
        void 중복_요청() throws Exception {
            agentsAvailable(false);
            requestChat(customer1Token);
            requestChat(customer1Token).andExpect(jsonPath("$.data.position").value(1));

            assertThat(roomRepository.findByCustomerIdOrderByIdDesc(customer1)).hasSize(1);
        }

        @Test
        @DisplayName("상담원·비회원 토큰은 403")
        void 고객_전용() throws Exception {
            Long agent = memberRepository.findByEmail("agent1@helpnest.local").orElseThrow().getId();
            requestChat(jwtProvider.createAccessToken(agent, "AGENT")).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("스케줄러: 상담원이 돌아오면 먼저 온 순서대로 연결된다")
    void 스케줄러_FIFO() throws Exception {
        agentsAvailable(false);
        requestChat(customer1Token);
        requestChat(customer2Token);

        // 상담원 1명만 복귀 — 첫 번째만 연결되고 두 번째는 다음 주기를 기다린다
        Member agent1 = memberRepository.findByEmail("agent1@helpnest.local").orElseThrow();
        agent1.changeAvailable(true);
        memberRepository.flush();
        scheduler.run();

        assertThat(roomOf(customer1).getStatus()).isEqualTo(ChatRoomStatus.OPEN);
        assertThat(roomOf(customer1).getAgentId()).isEqualTo(agent1.getId());
        // 상담원 1명이 부하 1 이 돼도 여전히 가용(available)이라 두 번째도 같은 상담원에게 연결된다
        assertThat(roomOf(customer2).getStatus()).isEqualTo(ChatRoomStatus.OPEN);
    }

    @Nested
    @DisplayName("POST /{roomId}/convert · DELETE /{roomId}")
    class ConvertAndCancel {

        private ChatRoom waitingSince(OffsetDateTime queuedAt, String... messages) {
            ChatRoom room = roomRepository.save(ChatRoom.waiting(customer1, queuedAt));
            for (String m : messages) {
                messageRepository.save(new ChatMessage(room.getId(), customer1, m));
            }
            return room;
        }

        @Test
        @DisplayName("5분 미만이면 409 CHAT_WAIT_NOT_EXPIRED")
        void 시간_미달() throws Exception {
            ChatRoom room = waitingSince(OffsetDateTime.now().minusMinutes(4), "환불 문의합니다");

            mockMvc.perform(post("/api/chat/rooms/{id}/convert", room.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CHAT_WAIT_NOT_EXPIRED"));
        }

        @Test
        @DisplayName("5분 경과 → 대기 메시지 합본으로 RECEIVED·CHAT 티켓, 방 CONVERTED")
        void 문의_전환() throws Exception {
            ChatRoom room = waitingSince(OffsetDateTime.now().minusMinutes(6), "환불 문의합니다", "주문번호 123");

            mockMvc.perform(post("/api/chat/rooms/{id}/convert", room.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ticketNo").isNotEmpty());

            ChatRoom converted = roomRepository.findById(room.getId()).orElseThrow();
            Ticket ticket = ticketRepository.findById(converted.getTicketId()).orElseThrow();
            assertThat(converted.getStatus()).isEqualTo(ChatRoomStatus.CONVERTED);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
            assertThat(ticket.getChannel()).isEqualTo(TicketChannel.CHAT);
            assertThat(ticket.getContent()).isEqualTo("환불 문의합니다\n주문번호 123");
        }

        @Test
        @DisplayName("메시지 없이 전환하면 400 CHAT_EMPTY_MESSAGES")
        void 빈_전환() throws Exception {
            ChatRoom room = waitingSince(OffsetDateTime.now().minusMinutes(6));

            mockMvc.perform(post("/api/chat/rooms/{id}/convert", room.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CHAT_EMPTY_MESSAGES"));
        }

        @Test
        @DisplayName("나가기 → CANCELED, 티켓 없음. 남의 방은 403")
        void 나가기() throws Exception {
            ChatRoom room = waitingSince(OffsetDateTime.now());

            mockMvc.perform(delete("/api/chat/rooms/{id}", room.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer2Token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/chat/rooms/{id}", room.getId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer1Token))
                    .andExpect(status().isOk());

            ChatRoom canceled = roomRepository.findById(room.getId()).orElseThrow();
            assertThat(canceled.getStatus()).isEqualTo(ChatRoomStatus.CANCELED);
            assertThat(canceled.getTicketId()).isNull();
        }
    }
}
