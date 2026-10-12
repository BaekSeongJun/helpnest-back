// @owner SSJ
package com.helpnest.domain.auth.verify;

import com.helpnest.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 이메일 인증 (back #117). permit·rate limit 은 SecurityConfig·RateLimitFilter(BSJ) */
@RestController
@RequestMapping("/api/auth/email")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService verificationService;
    private final EmailVerificationMailer mailer;

    public record VerifyRequest(
            @NotBlank(message = "링크가 올바르지 않습니다.")
            String token) {
    }

    public record ResendRequest(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
            String email) {
    }

    /** 링크 확인 → ACTIVE. 실패는 모두 AUTH_RESET_TOKEN_INVALID */
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody VerifyRequest req) {
        verificationService.verify(req.token());
        return ApiResponse.ok();
    }

    /** 재발송. 가입 여부와 무관하게 항상 200 */
    @PostMapping("/resend")
    public ApiResponse<Void> resend(@Valid @RequestBody ResendRequest req) {
        mailer.resend(req.email());
        return ApiResponse.ok();
    }
}
