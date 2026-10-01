// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 상담원 답변·내부 메모 등록 (docs/04 §7 POST /api/console/tickets/{id}/replies).
 *
 * <p>{@code isInternal} 을 {@code @NotNull Boolean} 으로 둔 것은 의도다. {@code boolean} 이면
 * 필드를 빠뜨린 요청이 조용히 false(= 고객에게 보이는 공개 답변)가 되어, 내부 메모로 쓰려던
 * 내용이 고객에게 노출된다. 명시적으로 받도록 강제한다.
 *
 * @param aiDraftId 사용한 AI 초안 id(신수진 AI_DRAFT). 직접 작성이면 null
 */
public record ConsoleReplyCreateRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 5000, message = "내용은 5,000자까지 입력할 수 있어요.")
        String content,

        @NotNull(message = "공개 답변인지 내부 메모인지 지정해 주세요.")
        Boolean isInternal,

        Long aiDraftId,

        @Size(max = 5, message = "첨부는 5개까지 올릴 수 있어요.")
        List<Long> attachmentIds) {
}
