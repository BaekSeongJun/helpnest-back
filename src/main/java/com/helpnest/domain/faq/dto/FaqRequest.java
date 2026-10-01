// @owner BSJ
package com.helpnest.domain.faq.dto;

import com.helpnest.domain.ticket.entity.TicketCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FAQ 작성·수정. published 를 생략하면 공개 */
public record FaqRequest(
        @NotNull(message = "유형을 선택해 주세요.")
        TicketCategory category,

        @NotBlank(message = "질문을 입력해 주세요.")
        @Size(max = 300, message = "질문은 300자 이하로 입력해 주세요.")
        String question,

        @NotBlank(message = "답변을 입력해 주세요.")
        @Size(max = 5000, message = "답변은 5,000자 이하로 입력해 주세요.")
        String answer,

        Boolean published) {

    public boolean publishedOrDefault() {
        return published == null || published;
    }
}
