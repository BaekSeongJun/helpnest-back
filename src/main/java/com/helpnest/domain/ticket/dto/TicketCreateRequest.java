// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.util.List;

import com.helpnest.domain.ticket.entity.TicketCategory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 접수 요청 (docs/04 §7). 회원이면 {@code guest} 를 보내지 않는다.
 *
 * <p>회원·비회원 판별은 이 본문이 아니라 Authorization 헤더로 한다. 본문의 guest 유무를
 * 믿으면 로그인한 사용자가 남의 이메일로 비회원 티켓을 만들 수 있다 — 검증은 서비스 책임이다.
 *
 * @param categoryHint 고객이 고른 유형. 최종 유형은 AI 분류가 정하므로(docs/05) 참고값이다.
 */
public record TicketCreateRequest(
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 200, message = "제목은 200자 이하로 입력해 주세요.")
        String title,

        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 5000, message = "내용은 5,000자까지 입력할 수 있어요.")
        String content,

        TicketCategory categoryHint,

        @Size(max = 5, message = "첨부는 5개까지 올릴 수 있어요.")
        List<Long> attachmentIds,

        @Valid GuestInfo guest) {

    /**
     * 비회원 접수 정보.
     *
     * @param password 조회용 비밀번호. 서버가 BCrypt 해시로만 저장하며 로그에 남기지 않는다
     *                 (docs/10 §3.3).
     */
    public record GuestInfo(
            @NotBlank(message = "이름을 입력해 주세요.")
            @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.")
            String name,

            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
            String email,

            @NotBlank(message = "조회 비밀번호를 입력해 주세요.")
            @Size(min = 4, max = 64, message = "조회 비밀번호는 4~64자로 입력해 주세요.")
            String password) {
    }
}
