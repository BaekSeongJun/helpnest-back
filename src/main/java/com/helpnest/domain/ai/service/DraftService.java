// @owner SSJ
package com.helpnest.domain.ai.service;

import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.DraftResponse;
import com.helpnest.domain.ai.dto.DraftResponse.Reference;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI-2 답변 초안 (docs/05 §4). FAQ·과거 답변·현재 대화를 참고 자료로 프롬프트를 만들어 LLM 호출.
 * LLM 호출은 트랜잭션 밖, 저장만 리포지토리 트랜잭션. 개인정보 마스킹·절단은 SpringAiLlmClient 가 한다.
 * 분류와 달리 상담원이 기다리는 동기 요청이라 실패는 503 으로 알린다(fallback 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    static final int FAQ_LIMIT = 3;
    static final int PAST_REPLY_LIMIT = 3;
    static final int RECENT_REPLY_LIMIT = 5;
    static final int LABEL_MAX = 40;

    static final String SYSTEM_PROMPT = """
            너는 HelpNest 고객상담원의 답변 초안을 작성하는 도우미다.
            - 정중한 존댓말, 3~6문장, 첫 문장은 공감/사과, 마지막은 추가 문의 안내.
            - [참고 자료]에 있는 정책·절차만 사용하고, 없는 내용(환불 금액, 날짜 약속 등)은 지어내지 말고 "[확인 필요: ...]"로 표시한다.
            - 고객 감정이 NEGATIVE면 불편에 대한 사과를 먼저 한다.
            응답은 JSON {"content": "..."} 하나만.
            """;

    private final TicketQueryPort ticketQueryPort;
    private final FaqQueryPort faqQueryPort;
    private final DraftContextRepository contextRepository;
    private final TicketAiResultRepository aiResultRepository;
    private final AiDraftRepository draftRepository;
    private final LlmClient llmClient;
    private final JsonMapper jsonMapper;

    /**
     * @param agentOnly 요청자가 AGENT 면 true → 본인 담당 티켓만 허용. LEAD·ADMIN 은 false
     * @throws BusinessException TICKET_NOT_FOUND(404), AI_NOT_ASSIGNEE(403), AI_PROVIDER_UNAVAILABLE(503)
     */
    public DraftResponse generate(Long ticketId, Long actorId, boolean agentOnly) {
        // 없는 티켓은 포트가 TICKET_NOT_FOUND 를 던진다
        TicketSummary ticket = ticketQueryPort.getTicketSummary(ticketId);
        Long agentId = contextRepository.findTicketAgent(ticketId)
                .map(DraftContextRepository.TicketAgent::getAgentId)
                .orElse(null);
        if (agentOnly && !Objects.equals(agentId, actorId)) {
            throw new BusinessException(AiErrorCode.NOT_ASSIGNEE);
        }

        Sentiment sentiment = aiResultRepository.findByTicketId(ticketId)
                .map(TicketAiResult::getSentiment)
                .orElse(Sentiment.NEUTRAL);
        // 유형만으로 검색: 제목 전체를 ILIKE 키워드로 쓰면 거의 일치하지 않는다
        List<FaqInfo> faqs = faqQueryPort.findPublishedByCategory(ticket.categoryHint(), null, FAQ_LIMIT);
        List<ResolvedReply> pastReplies = ticketQueryPort.findResolvedReplies(ticket.categoryHint(), PAST_REPLY_LIMIT);
        List<RecentReply> recent = contextRepository.findRecentPublicReplies(ticketId, RECENT_REPLY_LIMIT);

        String content = callLlm(userPrompt(ticket, sentiment, recent, faqs, pastReplies), ticketId);

        List<Reference> references = new ArrayList<>();
        faqs.forEach(f -> references.add(new Reference("FAQ", f.faqId(), label(f.question()))));
        pastReplies.forEach(r -> references.add(new Reference("REPLY", r.replyId(), label(r.content()))));

        AiDraft saved = draftRepository.save(AiDraft.builder()
                .ticketId(ticketId)
                .requestedBy(actorId)
                .content(content)
                .referenceRefs(jsonMapper.writeValueAsString(references))
                .model(llmClient.modelName())
                .build());
        return toResponse(saved);
    }

    public List<DraftResponse> list(Long ticketId) {
        return draftRepository.findByTicketIdOrderByIdDesc(ticketId).stream().map(this::toResponse).toList();
    }

    private String callLlm(String userPrompt, Long ticketId) {
        try {
            DraftResult r = llmClient.structured(SYSTEM_PROMPT, userPrompt, DraftResult.class);
            if (r != null && r.content() != null && !r.content().isBlank()) {
                return r.content().strip();
            }
            log.warn("AI 초안 빈 응답 ticketId={}", ticketId);
        } catch (Exception e) {
            log.warn("AI 초안 실패 ticketId={} cause={}", ticketId, e.toString());
        }
        throw new BusinessException(AiErrorCode.PROVIDER_UNAVAILABLE);
    }

    // docs/05 §4.2 User 프롬프트. [이전 대화]는 시간순으로 보이도록 뒤집는다
    static String userPrompt(TicketSummary ticket, Sentiment sentiment, List<RecentReply> recent,
            List<FaqInfo> faqs, List<ResolvedReply> pastReplies) {
        StringBuilder sb = new StringBuilder()
                .append("[티켓] 유형=").append(ticket.categoryHint()).append(", 감정=").append(sentiment).append('\n')
                .append("[고객 문의] ").append(ticket.title()).append('\n').append(ticket.content()).append('\n')
                .append("[이전 대화]\n");
        for (int i = recent.size() - 1; i >= 0; i--) {
            sb.append("- ").append(recent.get(i).getWriterType()).append(": ").append(recent.get(i).getContent())
                    .append('\n');
        }
        sb.append("[참고 자료]\n");
        faqs.forEach(f -> sb.append("- FAQ#").append(f.faqId()).append(": Q ").append(f.question())
                .append(" / A ").append(f.answer()).append('\n'));
        pastReplies.forEach(r -> sb.append("- 과거답변#").append(r.replyId()).append(": ").append(r.content())
                .append('\n'));
        return sb.toString();
    }

    private DraftResponse toResponse(AiDraft d) {
        List<Reference> refs = d.getReferenceRefs() == null ? List.of()
                : Arrays.asList(jsonMapper.readValue(d.getReferenceRefs(), Reference[].class));
        return new DraftResponse(d.getId(), d.getContent(), refs, d.getModel(), d.getCreatedAt());
    }

    private static String label(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= LABEL_MAX ? oneLine : oneLine.substring(0, LABEL_MAX) + "…";
    }
}
