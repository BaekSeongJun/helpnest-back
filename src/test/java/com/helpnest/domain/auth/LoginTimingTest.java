// @owner BSJ
package com.helpnest.domain.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 없는 이메일도 BCrypt 비교를 한 번 해야 응답 시간으로 가입 여부가 드러나지 않는다 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginTimingTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoSpyBean
    PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("없는 이메일로 로그인해도 matches 가 한 번 호출되고 401")
    void unknownEmailStillHashes() throws Exception {
        clearInvocations(passwordEncoder);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-timing@helpnest.local","password":"whatever1"}
                                """))
                .andExpect(status().isUnauthorized());

        verify(passwordEncoder, times(1)).matches(anyString(), anyString());
    }
}
