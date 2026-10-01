// @owner BSJ
package com.helpnest.domain.member.dto;

import com.helpnest.domain.member.entity.MemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 상담원·팀장 계정 생성. 검증 규칙은 SignupRequest 와 동일 + role(AGENT|LEAD 는 서비스에서 확인) */
public record AdminMemberCreateRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해 주세요.")
        String password,

        @NotBlank(message = "이름을 입력해 주세요.")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
        String name,

        @Pattern(regexp = "^[0-9-]{0,20}$", message = "연락처는 숫자와 - 만 20자 이하로 입력해 주세요.")
        String phone,

        @NotNull(message = "역할을 선택해 주세요.")
        MemberRole role) {
}
