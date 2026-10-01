// @owner BSJ
package com.helpnest.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.security.JwtProvider;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** POST /api/auth/guest — 비회원 조회 Guest 토큰 (FR-AUTH-08, docs/04 §2) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GuestAuthFlowTest {

    private static final String EMAIL = "guest-flow@example.com";
    private static final String PASSWORD = "lookup1234";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtDecoder jwtDecoder;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    MemberRepository memberRepository;

    String guestTicketNo;
    Long guestTicketId;
    String memberTicketNo;

    @BeforeEach
    void setUp() throws Exception {
        String guest = createTicket("""
                {"title":"비회원 문의","content":"배송이 안 와요","guest":{"name":"홍길동","email":"%s","password":"%s"}}
                """.formatted(EMAIL, PASSWORD), null);
        guestTicketNo = JsonPath.read(guest, "$.data.ticketNo");
        guestTicketId = ((Number) JsonPath.read(guest, "$.data.ticketId")).longValue();

        Long customerId = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        String member = createTicket("""
                {"title":"회원 문의","content":"환불 문의"}
                """, "Bearer " + jwtProvider.createAccessToken(customerId, "CUSTOMER"));
        memberTicketNo = JsonPath.read(member, "$.data.ticketNo");
    }

    @Test
    @DisplayName("성공: sub=guest:{id}, role=GUEST, ticketId 클레임, 30분. 이메일 대소문자·앞뒤 공백 무시")
    void issuesGuestToken() throws Exception {
        MvcResult result = mockMvc.perform(guest(guestTicketNo, "  " + EMAIL.toUpperCase() + " ", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketId").value(guestTicketId))
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.data.guestToken");
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo("guest:" + guestTicketId);
        assertThat(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM)).isEqualTo(JwtProvider.GUEST_ROLE);
        assertThat(JwtProvider.guestTicketId(jwt)).isEqualTo(guestTicketId);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();

        // 회원 전용 API 에는 쓸 수 없다
        mockMvc.perform(get("/api/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("틀린 비밀번호·틀린 이메일·없는 티켓·회원 티켓은 모두 같은 401 응답 (열거 방지)")
    void failuresAreIndistinguishable() throws Exception {
        List<String> bodies = List.of(
                failBody(guestTicketNo, EMAIL, "wrong-pass"),
                failBody(guestTicketNo, "other@example.com", PASSWORD),
                failBody("HN-19990101-999999", EMAIL, PASSWORD),
                failBody(memberTicketNo, "customer1@helpnest.local", PASSWORD));

        assertThat(bodies).allSatisfy(body -> assertThat(body).contains("AUTH_GUEST_INVALID"));
        assertThat(bodies).containsOnly(bodies.get(0));
    }

    @Test
    @DisplayName("빈 값은 400")
    void validation() throws Exception {
        mockMvc.perform(guest("", EMAIL, PASSWORD)).andExpect(status().isBadRequest());
    }

    private String failBody(String ticketNo, String email, String password) throws Exception {
        return mockMvc.perform(guest(ticketNo, email, password))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    private RequestBuilder guest(String ticketNo, String email, String password) {
        return post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ticketNo":"%s","email":"%s","password":"%s"}
                        """.formatted(ticketNo, email, password));
    }

    private String createTicket(String body, String authorization) throws Exception {
        var req = post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content(body);
        if (authorization != null) {
            req.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(req).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
}
