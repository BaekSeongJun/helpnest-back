// @owner SSJ
package com.helpnest.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.DraftResponse;
import com.helpnest.domain.ai.dto.DraftResult;
import com.helpnest.domain.ai.entity.AiDraft;
import com.helpnest.domain.ai.entity.TicketAiResult;
import com.helpnest.domain.ai.error.AiErrorCode;
import com.helpnest.domain.ai.repository.AiDraftRepository;
import com.helpnest.domain.ai.repository.DraftContextRepository;
import com.helpnest.domain.ai.repository.DraftContextRepository.RecentReply;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.faq.port.FaqInfo;
import com.helpnest.domain.faq.port.FaqQueryPort;
import com.helpnest.domain.ticket.port.ResolvedReply;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.domain.ticket.port.TicketSummary;
import com.helpnest.global.error.BusinessException;
import com.helpnest.infra.llm.LlmClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class DraftServiceTest {

    private static final long TICKET = 5L;
    private static final long AGENT = 7L;

    private final TicketQueryPort ticketQueryPort = mock(TicketQueryPort.class);
    private final FaqQueryPort faqQueryPort = mock(FaqQueryPort.class);
    private final DraftContextRepository contextRepository = mock(DraftContextRepository.class);
    private final TicketAiResultRepository aiResultRepository = mock(TicketAiResultRepository.class);
    private final AiDraftRepository draftRepository = mock(AiDraftRepository.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final DraftService service = new DraftService(ticketQueryPort, faqQueryPort, contextRepository,
            aiResultRepository, draftRepository, llm, JsonMapper.builder().build());

    private static RecentReply reply(String writerType, String content) {
        return new RecentReply() {
            @Override
            public String getWriterType() {
                return writerType;
            }

            @Override
            public String getContent() {
                return content;
            }
        };
    }

    @BeforeEach
    void setUp() {
        when(ticketQueryPort.getTicketSummary(TICKET)).thenReturn(new TicketSummary("환불 문의", "환불이 안 돼요", "REFUND"));
        when(contextRepository.findTicketAgent(TICKET)).thenReturn(Optional.of(() -> AGENT));
        when(aiResultRepository.findByTicketId(TICKET)).thenReturn(Optional.of(TicketAiResult.builder()
                .ticketId(TICKET).sentiment(Sentiment.NEGATIVE).status(TicketAiResult.Status.SUCCESS).build()));
        when(faqQueryPort.findPublishedByCategory("REFUND", null, 3))
                .thenReturn(List.of(new FaqInfo(3L, "REFUND", "환불은 얼마나 걸리나요?", "영업일 3일")));
        when(ticketQueryPort.findResolvedReplies("REFUND", 3)).thenReturn(List.of(new ResolvedReply(41L, "환불 접수 완료")));
        // 최신순으로 돌아온다
        when(contextRepository.findRecentPublicReplies(TICKET, 5))
                .thenReturn(List.of(reply("CUSTOMER", "두번째"), reply("AGENT", "첫번째")));
        when(llm.modelName()).thenReturn("test-model");
        when(draftRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("프롬프트에 감정·FAQ·과거답변·이전 대화(시간순) 포함, referenceRefs JSON 저장")
    void generate() {
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.structured(anyString(), prompt.capture(), eq(DraftResult.class)))
                .thenReturn(new DraftResult(" 불편을 드려 죄송합니다. "));

        DraftResponse res = service.generate(TICKET, AGENT, true);

        assertThat(prompt.getValue()).contains("유형=REFUND, 감정=NEGATIVE", "환불이 안 돼요",
                "FAQ#3: Q 환불은 얼마나 걸리나요? / A 영업일 3일", "과거답변#41: 환불 접수 완료");
        assertThat(prompt.getValue().indexOf("첫번째")).isLessThan(prompt.getValue().indexOf("두번째"));
        assertThat(res.content()).isEqualTo("불편을 드려 죄송합니다.");
        assertThat(res.references()).extracting(DraftResponse.Reference::type, DraftResponse.Reference::id)
                .containsExactly(tuple("FAQ", 3L),
                        tuple("REPLY", 41L));

        ArgumentCaptor<AiDraft> saved = ArgumentCaptor.forClass(AiDraft.class);
        verify(draftRepository).save(saved.capture());
        assertThat(saved.getValue().getReferenceRefs()).contains("\"type\":\"FAQ\"", "\"id\":3", "\"id\":41");
        assertThat(saved.getValue().getRequestedBy()).isEqualTo(AGENT);
        assertThat(saved.getValue().getModel()).isEqualTo("test-model");
    }

    @Test
    @DisplayName("AGENT 가 담당이 아니면 403 AI_NOT_ASSIGNEE, LLM 호출 없음")
    void notAssignee() {
        assertThatThrownBy(() -> service.generate(TICKET, 99L, true))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.NOT_ASSIGNEE));
        verify(llm, never()).structured(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("LEAD 이상은 담당이 아니어도 생성")
    void leadAllowed() {
        when(llm.structured(anyString(), anyString(), eq(DraftResult.class))).thenReturn(new DraftResult("초안"));

        assertThat(service.generate(TICKET, 99L, false).content()).isEqualTo("초안");
    }

    @Test
    @DisplayName("LLM 실패·빈 응답이면 503 AI_PROVIDER_UNAVAILABLE, 저장 없음")
    void llmFailure() {
        when(llm.structured(anyString(), anyString(), eq(DraftResult.class)))
                .thenThrow(new RuntimeException("timeout"))
                .thenReturn(new DraftResult(" "));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.generate(TICKET, AGENT, true))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AiErrorCode.PROVIDER_UNAVAILABLE));
        }
        verify(draftRepository, never()).save(any());
    }

    @Test
    @DisplayName("분류 결과가 없으면 감정 NEUTRAL")
    void noClassification() {
        when(aiResultRepository.findByTicketId(TICKET)).thenReturn(Optional.empty());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.structured(anyString(), prompt.capture(), eq(DraftResult.class))).thenReturn(new DraftResult("초안"));

        service.generate(TICKET, AGENT, true);

        assertThat(prompt.getValue()).contains("감정=NEUTRAL");
    }
}
