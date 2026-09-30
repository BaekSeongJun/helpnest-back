// @owner BSJ
package com.helpnest.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** local 프로필 시드(R__seed_BSJ_member.sql) 계정으로 로그인된다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SeedLoginTest {

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("역할별 시드 계정으로 로그인")
    @CsvSource({
            "admin@helpnest.local, ADMIN",
            "lead@helpnest.local, LEAD",
            "agent1@helpnest.local, AGENT",
            "agent3@helpnest.local, AGENT",
            "customer1@helpnest.local, CUSTOMER"
    })
    void login(String email, String role) throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Test1234!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.member.role").value(role));
    }
}
