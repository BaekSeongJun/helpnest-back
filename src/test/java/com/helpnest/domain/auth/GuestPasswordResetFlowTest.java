// @owner BSJ
package com.helpnest.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.ticket.port.TicketGuestPort;
import com.helpnest.infra.mail.PasswordResetMailCommand;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비회원 조회 비밀번호 재설정 (FR-AUTH-09). TicketGuestPort(박민재)는 updateGuestPassword 가 아직 스텁이라
 * 모킹하고, "해시를 포트로 넘기는지"까지 확인한다. 실제 반영은 박민재 구현 후 E2E 로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class GuestPasswordResetFlowTest {

    private static final String TICKET_NO = "HN-20261001-000777";
    private static final String EMAIL = "Guest@Example.com";
    private static final long TICKET_ID = 777L;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    PasswordEncoder passwordEncoder;
    @MockitoBean
    TicketGuestPort ticketGuestPort;

    /** Mockito 는 Long 반환을 0L 로 채운다 — 실제 포트처럼 "일치 없음 = null" 로 맞춘다 */
    @BeforeEach
    void noMatchByDefault() {
        given(ticketGuestPort.verifyGuest(anyString(), anyString())).willReturn(null);
    }

    @Test
    @DisplayName("요청: 티켓번호·이메일이 안 맞아도 같은 200, 맞을 때만 비회원 메일(조회 재설정 링크)")
    void requestSameResponse() throws Exception {
        requestReset("HN-없는-티켓").andExpect(status().isOk());
        assertThat(mails()).isEmpty();

        given(ticketGuestPort.verifyGuest(TICKET_NO, EMAIL)).willReturn(TICKET_ID);
        requestReset(" " + TICKET_NO + " ").andExpect(status().isOk());   // 앞뒤 공백은 무시
        assertThat(mails()).singleElement().satisfies(m -> {
            assertThat(m.email()).isEqualTo(EMAIL);
            assertThat(m.guest()).isTrue();
            assertThat(m.resetUrl()).startsWith("http://localhost:3000/inquiry/lookup/reset?token=");
        });
    }

    @Test
    @DisplayName("재설정: 원문이 아닌 BCrypt 해시를 포트로 넘기고, 같은 링크는 다시 못 쓴다")
    void resetPassesHash() throws Exception {
        given(ticketGuestPort.verifyGuest(TICKET_NO, EMAIL)).willReturn(TICKET_ID);
        String token = requestToken();

        reset(token, "new1").andExpect(status().isOk());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(ticketGuestPort).updateGuestPassword(eq(TICKET_ID), hash.capture());
        assertThat(hash.getValue()).isNotEqualTo("new1");
        assertThat(passwordEncoder.matches("new1", hash.getValue())).isTrue();

        reset(token, "new2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));
        verify(ticketGuestPort, times(1)).updateGuestPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("재설정: 회원 비밀번호 재설정 링크는 비회원 쪽에서 쓸 수 없고, 4자 미만은 400")
    void rejectsMemberTokenAndShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"customer1@helpnest.local\"}"))
                .andExpect(status().isOk());
        reset(tokenOf(mails().get(0)), "abcd").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("AUTH_RESET_TOKEN_INVALID"));

        given(ticketGuestPort.verifyGuest(TICKET_NO, EMAIL)).willReturn(TICKET_ID);
        reset(requestToken(), "abc").andExpect(status().isBadRequest());
        verify(ticketGuestPort, never()).updateGuestPassword(any(), any());
    }

    private List<PasswordResetMailCommand> mails() {
        return events.stream(PasswordResetMailCommand.class).toList();
    }

    private String requestToken() throws Exception {
        requestReset(TICKET_NO).andExpect(status().isOk());
        List<PasswordResetMailCommand> sent = mails();
        return tokenOf(sent.get(sent.size() - 1));
    }

    private static String tokenOf(PasswordResetMailCommand mail) {
        return mail.resetUrl().substring(mail.resetUrl().indexOf("token=") + "token=".length());
    }

    private ResultActions requestReset(String ticketNo) throws Exception {
        return mockMvc.perform(post("/api/auth/guest/reset-request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketNo\":\"%s\",\"email\":\"%s\"}".formatted(ticketNo, EMAIL)));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/guest/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword)));
    }
}
