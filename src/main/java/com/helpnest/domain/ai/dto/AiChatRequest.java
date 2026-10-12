// @owner SSJ
package com.helpnest.domain.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** AI 상담 요청 (docs/04 §12). history 는 화면이 들고 있다가 매번 보낸다(서버 저장 없음) */
public record AiChatRequest(
        @NotBlank @Size(max = 500) String question,
        List<@Valid Turn> history) {

    public enum Role { USER, AI }

    public record Turn(@NotNull Role role, @NotNull String content) {
    }
}
