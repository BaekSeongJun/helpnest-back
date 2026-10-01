// @owner BSJ
package com.helpnest.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재설정 링크의 토큰 + 새 비밀번호 (CM-04). 비밀번호 규칙은 SignupRequest 와 같다 */
public record PasswordResetRequest(
        @NotBlank(message = "링크가 올바르지 않습니다.")
        String token,

        @NotBlank(message = "새 비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해 주세요.")
        String newPassword) {
}
