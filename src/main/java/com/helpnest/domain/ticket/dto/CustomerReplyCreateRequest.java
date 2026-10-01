// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 고객 추가 답글 (docs/04 §7 POST /api/tickets/{id}/replies).
 *
 * <p>고객은 내부 메모를 만들 수 없으므로 isInternal 을 받지 않는다 — 받아서 무시하는 대신
 * 아예 두지 않아 잘못 보낼 여지를 없앤다. RESOLVED 상태였다면 재문의로 IN_PROGRESS 가 된다.
 */
public record CustomerReplyCreateRequest(
        @NotBlank(message = "내용을 입력해 주세요.")
        @Size(max = 5000, message = "내용은 5,000자까지 입력할 수 있어요.")
        String content,

        @Size(max = 5, message = "첨부는 5개까지 올릴 수 있어요.")
        List<Long> attachmentIds) {
}
