// @owner BSJ
package com.helpnest.global.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("성공 응답은 success=true, data, error=null 형식")
    void ok() throws Exception {
        mockMvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("hello"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("BusinessException 은 ErrorCode 의 상태·코드와 지정 메시지로 응답")
    void business() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.error.message").value("10분 뒤에 다시 시도해 주세요."));
    }

    @Test
    @DisplayName("@Valid 실패는 400 COMMON_INVALID_INPUT + 필드 메시지")
    void validation() throws Exception {
        mockMvc.perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
                .andExpect(jsonPath("$.error.message").value("제목을 입력해 주세요"));
    }

    @Test
    @DisplayName("JSON 본문이 깨지면 400 COMMON_INVALID_INPUT")
    void unreadable() throws Exception {
        mockMvc.perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
    }

    @Test
    @DisplayName("예상 못 한 예외는 500 COMMON_INTERNAL_ERROR, 내부 메시지는 노출하지 않음")
    void unexpected() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("일시적인 오류가 발생했습니다."));
    }

    record TitleRequest(@NotBlank(message = "제목을 입력해 주세요") String title) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("hello");
        }

        @GetMapping("/test/business")
        ApiResponse<Void> business() {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS, "10분 뒤에 다시 시도해 주세요.");
        }

        @PostMapping("/test/valid")
        ApiResponse<Void> valid(@Valid @RequestBody TitleRequest request) {
            return ApiResponse.ok();
        }

        @GetMapping("/test/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("DB 비밀번호 같은 내부 정보");
        }
    }
}
