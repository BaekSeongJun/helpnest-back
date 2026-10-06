// @owner BSJ
package com.helpnest.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재설정 링크의 토큰 + 새 조회 비밀번호 (CU-06 ②). 규칙은 문의 접수(TicketCreateRequest)와 같은 4~64자 */
public record GuestPasswordResetRequest(
        @NotBlank(message = "링크가 올바르지 않습니다.")
        String token,

        @NotBlank(message = "새 조회 비밀번호를 입력해 주세요.")
        @Size(min = 4, max = 64, message = "조회 비밀번호는 4~64자로 입력해 주세요.")
        String newPassword) {
}
