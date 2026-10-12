// @owner SSJ
package com.helpnest.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.ai.dto.AiChatRequest.Role;
import com.helpnest.domain.ai.dto.AiChatRequest.Turn;
import com.helpnest.domain.ai.dto.AiChatResponse;
import com.helpnest.domain.ai.dto.AiChatResponse.Reference;
import com.helpnest.domain.ai.dto.AiChatResult;
import com.helpnest.domain.faq.port.FaqInfo;
import com.helpnest.domain.faq.port.FaqQueryPort;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.RateLimiter;
import com.helpnest.infra.llm.LlmClient;
import com.helpnest.infra.llm.LlmException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiChatServiceTest {

    private static final long MEMBER = 9L;
    private static final FaqInfo FAQ = new FaqInfo(3L, "REFUND", "환불은 언제 되나요?", "결제 취소 후 3~5영업일 걸립니다.");

    private final FaqQueryPort faqQueryPort = mock(FaqQueryPort.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final AiChatService service = new AiChatService(faqQueryPort, new RateLimiter(), llm);

    @Test
    @DisplayName("근거 FAQ 있음 → LLM 답변 + 참고 FAQ")
    void answered() {
        when(faqQueryPort.findPublishedByQuestion("환불 언제 돼요?", 3)).thenReturn(List.of(FAQ));
        when(llm.structuredOnce(anyString(), anyString(), eq(AiChatResult.class)))
                .thenReturn(new AiChatResult(" 3~5영업일 안에 환불됩니다. "));

        AiChatResponse r = service.chat(MEMBER, "환불 언제 돼요?", null);

        assertThat(r.answer()).isEqualTo("3~5영업일 안에 환불됩니다.");
        assertThat(r.handoff()).isFalse();
        assertThat(r.references()).containsExactly(new Reference(3L, "환불은 언제 되나요?"));
    }

    @Test
    @DisplayName("근거 FAQ 없음 → handoff, LLM 호출 없음")
    void noFaq() {
        when(faqQueryPort.findPublishedByQuestion(anyString(), eq(3))).thenReturn(List.of());

        AiChatResponse r = service.chat(MEMBER, "택배 기사님 연락처", null);

        assertThat(r.handoff()).isTrue();
        assertThat(r.answer()).isEqualTo(AiChatService.HANDOFF_ANSWER);
        assertThat(r.references()).isEmpty();
        verify(llm, never()).structuredOnce(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("LLM 실패·FAQ 밖이라 빈 답변 → handoff (오류 아님)")
    void llmFailedOrEmpty() {
        when(faqQueryPort.findPublishedByQuestion(anyString(), eq(3))).thenReturn(List.of(FAQ));
        when(llm.structuredOnce(anyString(), anyString(), eq(AiChatResult.class)))
                .thenThrow(new LlmException("timeout", null))
                .thenReturn(new AiChatResult(""));

        for (int i = 0; i < 2; i++) {
            AiChatResponse r = service.chat(MEMBER, "환불", null);
            assertThat(r.handoff()).isTrue();
            assertThat(r.references()).isEmpty();
        }
    }

    @Test
    @DisplayName("회원당 10분 30회 초과 → 429, 다른 회원은 별도 한도")
    void rateLimited() {
        when(faqQueryPort.findPublishedByQuestion(anyString(), eq(3))).thenReturn(List.of());
        IntStream.range(0, 30).forEach(i -> service.chat(MEMBER, "환불", null));

        assertThatThrownBy(() -> service.chat(MEMBER, "환불", null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode().code()).isEqualTo("COMMON_TOO_MANY_REQUESTS"));
        assertThat(service.chat(MEMBER + 1, "환불", null).handoff()).isTrue();
    }

    @Test
    @DisplayName("프롬프트: 질문이 맨 앞, history 는 최근 6개만·항목당 300자")
    void prompt() {
        List<Turn> history = IntStream.range(0, 8)
                .mapToObj(i -> new Turn(i % 2 == 0 ? Role.USER : Role.AI, "대화" + i + "가".repeat(400)))
                .toList();

        String p = AiChatService.userPrompt("환불 언제 돼요?", List.of(FAQ), history);

        assertThat(p).startsWith("[고객 질문] 환불 언제 돼요?\n");
        assertThat(p).contains("- FAQ#3: Q 환불은 언제 되나요? / A 결제 취소 후 3~5영업일 걸립니다.");
        assertThat(p).doesNotContain("대화0", "대화1").contains("USER: 대화2", "AI: 대화7");
        assertThat(p).doesNotContain("가".repeat(301));
    }
}
