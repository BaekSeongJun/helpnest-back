// @owner BSJ
package com.helpnest.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 비회원 문의 조회 (CU-05). 형식 오류도 존재 여부를 드러내지 않도록 길이만 본다 */
public record GuestLoginRequest(
        @NotBlank(message = "티켓번호를 입력해 주세요.")
        @Size(max = 30, message = "티켓번호가 올바르지 않습니다.")
        String ticketNo,

        @NotBlank(message = "이메일을 입력해 주세요.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String email,

        @NotBlank(message = "조회 비밀번호를 입력해 주세요.")
        @Size(max = 64, message = "조회 비밀번호는 64자 이하로 입력해 주세요.")
        String password) {
}
