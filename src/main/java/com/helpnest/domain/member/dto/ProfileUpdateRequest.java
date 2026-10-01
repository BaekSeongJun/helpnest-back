// @owner BSJ
package com.helpnest.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 내 정보 수정 (CU-10). 규칙은 SignupRequest 와 같다. phone 을 비우면 삭제 */
public record ProfileUpdateRequest(
        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
        String name,

        @Pattern(regexp = "^[0-9-]{0,20}$", message = "연락처는 숫자와 - 만 20자 이하로 입력해 주세요.")
        String phone) {
}
