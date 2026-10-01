// @owner SSJ
package com.helpnest.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.ai.dto.ClassificationResult;
import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import com.helpnest.domain.ai.entity.TicketAiResult;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ticket.port.TicketClassificationPort;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.domain.ticket.port.TicketSummary;
import com.helpnest.infra.llm.LlmClient;
import com.helpnest.infra.llm.LlmException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class ClassifyServiceTest {

    private static final Long TICKET_ID = 7L;

    private final TicketQueryPort queryPort = mock(TicketQueryPort.class);
    private final TicketClassificationPort classificationPort = mock(TicketClassificationPort.class);
    private final TicketAiResultRepository repository = mock(TicketAiResultRepository.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final ClassifyService service = new ClassifyService(queryPort, classificationPort, repository, llm,
            JsonMapper.builder().build());

    @BeforeEach
    void setUp() {
        when(queryPort.getTicketSummary(TICKET_ID)).thenReturn(new TicketSummary("배송 문의", "아직 안 왔어요", "DELIVERY"));
        when(repository.findByTicketId(TICKET_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(llm.modelName()).thenReturn("test-model");
    }

    private void llmReturns(ClassificationResult r) {
        when(llm.structured(eq(ClassifyService.SYSTEM_PROMPT), anyString(), eq(ClassificationResult.class)))
                .thenReturn(r);
    }

    @Test
    @DisplayName("성공 → SUCCESS 저장 후 applyClassification(감정 보정된 우선순위)")
    void success() {
        llmReturns(new ClassificationResult(Category.DELIVERY, Urgency.NORMAL, Sentiment.NEGATIVE, "배송 지연 불만", 0.864));

        TicketAiResult result = service.classify(TICKET_ID);

        assertThat(result.getStatus()).isEqualTo(TicketAiResult.Status.SUCCESS);
        assertThat(result.getCategory()).isEqualTo(Category.DELIVERY);
        assertThat(result.getUrgency()).isEqualTo(Urgency.NORMAL); // 원본 urgency 는 그대로 저장
        assertThat(result.getConfidence()).isEqualByComparingTo(new BigDecimal("0.86"));
        assertThat(result.getModel()).isEqualTo("test-model");
        assertThat(result.getRawResponse()).contains("\"category\":\"DELIVERY\"");
        verify(classificationPort).applyClassification(TICKET_ID, "DELIVERY", "HIGH", "NEGATIVE");
        verify(classificationPort, never()).applyClassificationFailed(any());
    }

    @Test
    @DisplayName("프롬프트에 고객 선택 유형·제목·본문 포함")
    void prompt() {
        assertThat(ClassifyService.userPrompt(new TicketSummary("제목", "본문", "ETC")))
                .isEqualTo("[고객 선택 유형] ETC\n[제목] 제목\n[본문] 본문");
    }

    @ParameterizedTest(name = "{0} + {1} → {2}")
    @CsvSource({
            "LOW, NEGATIVE, NORMAL",
            "NORMAL, NEGATIVE, HIGH",
            "HIGH, NEGATIVE, URGENT",
            "URGENT, NEGATIVE, URGENT",
            "HIGH, NEUTRAL, HIGH",
            "LOW, POSITIVE, LOW",
    })
    @DisplayName("NEGATIVE 면 1단계 상향, URGENT 상한")
    void priority(Urgency urgency, Sentiment sentiment, Urgency expected) {
        assertThat(ClassifyService.priority(urgency, sentiment)).isEqualTo(expected);
    }

    @Test
    @DisplayName("LLM 예외 → FAILED 저장 후 applyClassificationFailed")
    void llmFailure() {
        when(llm.structured(anyString(), anyString(), eq(ClassificationResult.class)))
                .thenThrow(new LlmException("timeout", null));

        TicketAiResult result = service.classify(TICKET_ID);

        assertThat(result.getStatus()).isEqualTo(TicketAiResult.Status.FAILED);
        assertThat(result.getCategory()).isNull();
        verify(classificationPort).applyClassificationFailed(TICKET_ID);
        verify(classificationPort, never()).applyClassification(any(), any(), any(), any());
    }

    @Test
    @DisplayName("응답 필드 누락 → 실패 처리")
    void invalidResponse() {
        llmReturns(new ClassificationResult(null, Urgency.HIGH, Sentiment.NEUTRAL, "s", 0.5));

        assertThat(service.classify(TICKET_ID).getStatus()).isEqualTo(TicketAiResult.Status.FAILED);
        verify(classificationPort).applyClassificationFailed(TICKET_ID);
    }

    @Test
    @DisplayName("포트가 값을 거부해도 기본값 배정으로 넘긴다")
    void portRejects() {
        llmReturns(new ClassificationResult(Category.REFUND, Urgency.HIGH, Sentiment.NEUTRAL, "s", 0.5));
        doThrow(new IllegalArgumentException("bad"))
                .when(classificationPort).applyClassification(any(), any(), any(), any());

        assertThat(service.classify(TICKET_ID).getStatus()).isEqualTo(TicketAiResult.Status.FAILED);
        verify(classificationPort).applyClassificationFailed(TICKET_ID);
    }

    @Test
    @DisplayName("재분류 → 기존 행 갱신(새 행 생성 안 함), 실패 시 이전 분류값 제거")
    void reclassifyUpdatesSameRow() {
        TicketAiResult existing = TicketAiResult.builder().ticketId(TICKET_ID).category(Category.ETC)
                .urgency(Urgency.LOW).sentiment(Sentiment.NEUTRAL).status(TicketAiResult.Status.SUCCESS).build();
        when(repository.findByTicketId(TICKET_ID)).thenReturn(Optional.of(existing));

        llmReturns(new ClassificationResult(Category.PAYMENT, Urgency.HIGH, Sentiment.NEUTRAL, "결제 오류", 0.9));
        assertThat(service.classify(TICKET_ID)).isSameAs(existing);
        assertThat(existing.getCategory()).isEqualTo(Category.PAYMENT);

        when(llm.structured(anyString(), anyString(), eq(ClassificationResult.class)))
                .thenThrow(new LlmException("timeout", null));
        assertThat(service.classify(TICKET_ID)).isSameAs(existing);
        assertThat(existing.getStatus()).isEqualTo(TicketAiResult.Status.FAILED);
        assertThat(existing.getCategory()).isNull();
    }
}
