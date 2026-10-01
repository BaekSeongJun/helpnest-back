// @owner BSJ
package com.helpnest.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 비회원 조회 비밀번호 재설정 메일 요청 (CU-06 ①). GuestLoginRequest 처럼 형식은 길이만 본다 */
public record GuestPasswordResetMailRequest(
        @NotBlank(message = "티켓번호를 입력해 주세요.")
        @Size(max = 30, message = "티켓번호가 올바르지 않습니다.")
        String ticketNo,

        @NotBlank(message = "이메일을 입력해 주세요.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String email) {
}
