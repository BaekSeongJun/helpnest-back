// @owner PMJ
package com.helpnest.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;
import com.helpnest.domain.chat.error.ChatErrorCode;
import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.domain.chat.service.ChatMessageService;
import com.helpnest.domain.chat.service.ChatService;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.port.TicketClassificationPort;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProvider;

/**
 * 채팅 메시지·첫 응답·종료 통합 테스트 (FR-CHT-02·03·06).
 *
 * <p>STOMP 송신은 브로커를 띄우지 않고 {@link ChatMessageService#send} 를 직접 부른다. 프레임 권한은
 * {@code StompAuthInterceptorTest}, 핸들러 연결은 한 줄 위임이라 여기서는 비즈니스 규칙만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("채팅 메시지·첫 응답·종료")
class ChatMessageApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    ChatService chatService;
    @Autowired
    ChatMessageService messageService;
    @Autowired
    ChatRoomRepository roomRepository;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketClassificationPort classificationPort;

    private Long customer;
    private Long otherCustomer;
    private ChatRoom room;
    private Long agent;

    /** 상담원이 있는 상태에서 채팅을 열어 둔다 — 티켓은 ASSIGNED, 첫 응답 전 */
    @BeforeEach
    void openRoom() {
        customer = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        otherCustomer = memberRepository.findByEmail("customer2@helpnest.local").orElseThrow().getId();
        Long roomId = chatService.openOrGetRoom(customer);
        assertThat(chatService.tryMatch(roomId)).as("시드 상담원이 available 이어야 한다").isTrue();
        room = roomRepository.findById(roomId).orElseThrow();
        agent = room.getAgentId();
    }

    private Ticket ticket() {
        return ticketRepository.findById(room.getTicketId()).orElseThrow();
    }

    private String token(Long memberId, String role) {
        return "Bearer " + jwtProvider.createAccessToken(memberId, role);
    }

    private ResultActions close(Long agentId, String body) throws Exception {
        return mockMvc.perform(patch("/api/chat/rooms/{id}/close", room.getId())
                .header(HttpHeaders.AUTHORIZATION, token(agentId, "AGENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("고객 메시지는 첫 응답이 아니다 — 티켓은 ASSIGNED, firstRespondedAt 없음")
    void 고객_메시지() {
        messageService.send(room.getId(), customer, "안녕하세요");

        assertThat(ticket().getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket().getFirstRespondedAt()).isNull();
    }

    @Test
    @DisplayName("상담원 첫 메시지 → firstRespondedAt 기록 + IN_PROGRESS, 두 번째 메시지는 시각 불변")
    void 상담원_첫_응답() {
        messageService.send(room.getId(), agent, "무엇을 도와드릴까요?");
        OffsetDateTime first = ticket().getFirstRespondedAt();

        messageService.send(room.getId(), agent, "주문번호를 알려 주세요.");

        assertThat(first).isNotNull();
        assertThat(ticket().getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(ticket().getFirstRespondedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("비참여자 송신 403, 공백·5,000자 초과 400, 종료된 방 409")
    void 송신_거부() {
        assertError(() -> messageService.send(room.getId(), otherCustomer, "끼어들기"), ChatErrorCode.NOT_PARTICIPANT);
        assertError(() -> messageService.send(room.getId(), customer, "   "), ChatErrorCode.INVALID_CONTENT);
        assertError(() -> messageService.send(room.getId(), customer, "가".repeat(5_001)), ChatErrorCode.INVALID_CONTENT);

        chatService.close(room.getId(), agent, false);
        assertError(() -> messageService.send(room.getId(), customer, "아직 있어요"), ChatErrorCode.INVALID_STATE);
    }

    @Test
    @DisplayName("상담원이 말없이 종료·해결해도 ASSIGNED 에서 막히지 않고 RESOLVED, 첫 응답은 기록하지 않는다")
    void 무응답_종료_해결() throws Exception {
        close(agent, "{\"resolve\":true}").andExpect(status().isOk());

        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(ChatRoomStatus.CLOSED);
        assertThat(ticket().getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticket().getFirstRespondedAt()).isNull();
    }

    @Test
    @DisplayName("resolve 없이 종료하면 방만 닫고 티켓은 그대로, 담당 아닌 상담원은 403")
    void 종료만() throws Exception {
        Long otherAgent = memberRepository.findByEmail("agent1@helpnest.local").orElseThrow().getId();
        if (otherAgent.equals(agent)) {
            otherAgent = memberRepository.findByEmail("agent2@helpnest.local").orElseThrow().getId();
        }
        close(otherAgent, "{}").andExpect(status().isForbidden());

        messageService.send(room.getId(), agent, "확인해 보고 연락드리겠습니다.");
        close(agent, "{\"resolve\":false}").andExpect(status().isOk());

        assertThat(ticket().getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("이전 메시지: before 커서로 오래된 순 반환, 비참여자 403")
    void 이전_메시지() throws Exception {
        messageService.send(room.getId(), customer, "1");
        messageService.send(room.getId(), agent, "2");
        Long third = messageService.send(room.getId(), customer, "3").messageId();

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", room.getId()).param("before", third.toString())
                        .header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].content").value("1"))
                .andExpect(jsonPath("$.data[1].content").value("2"))
                .andExpect(jsonPath("$.data[1].senderName").isNotEmpty());

        mockMvc.perform(get("/api/chat/rooms/{id}/messages", room.getId())
                        .header(HttpHeaders.AUTHORIZATION, token(otherCustomer, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("방 목록: 고객은 본인 방, 상담원은 담당 방")
    void 방_목록() throws Exception {
        mockMvc.perform(get("/api/chat/rooms").header(HttpHeaders.AUTHORIZATION, token(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.data[0].roomId").value(room.getId()))
                .andExpect(jsonPath("$.data[0].agentName").isNotEmpty());
        mockMvc.perform(get("/api/chat/rooms").header(HttpHeaders.AUTHORIZATION, token(agent, "AGENT")))
                .andExpect(jsonPath("$.data[0].roomId").value(room.getId()))
                .andExpect(jsonPath("$.data[0].customerName").isNotEmpty());
    }

    /** FR-CHT-06 회귀 — AI 분류가 늦게 와도 채팅으로 이미 배정된 상담원이 바뀌면 안 된다 */
    @Test
    @DisplayName("이미 ASSIGNED 인 CHAT 티켓에 분류가 오면 우선순위만 바뀌고 담당자는 그대로다")
    void 분류_재배정_없음() {
        classificationPort.applyClassification(room.getTicketId(), "REFUND", "HIGH", "NEGATIVE");

        assertThat(ticket().getAgentId()).isEqualTo(agent);
        assertThat(ticket().getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ticket().getPriority().name()).isEqualTo("HIGH");
    }

    private static void assertError(Runnable action, ChatErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(expected);
    }
}
