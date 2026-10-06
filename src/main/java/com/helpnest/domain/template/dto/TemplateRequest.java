// @owner BSJ
package com.helpnest.domain.template.dto;

import com.helpnest.domain.ticket.entity.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 템플릿 작성·수정. active 를 생략하면 사용 중. 본문 상한은 답변 본문(5,000자)과 같다 */
public record TemplateRequest(
        @NotNull(message = "유형을 선택해 주세요.")
        TicketCategory category,

        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 100, message = "제목은 100자 이하로 입력해 주세요.")
        String title,

        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 5000, message = "본문은 5,000자 이하로 입력해 주세요.")
        String content,

        Boolean active) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}
