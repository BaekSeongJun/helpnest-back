// @owner SSJ
package com.helpnest.domain.ai.service;

import com.helpnest.domain.ai.dto.AiChatRequest.Turn;
import com.helpnest.domain.ai.dto.AiChatResponse;
import com.helpnest.domain.ai.dto.AiChatResponse.Reference;
import com.helpnest.domain.ai.dto.AiChatResult;
import com.helpnest.domain.faq.port.FaqInfo;
import com.helpnest.domain.faq.port.FaqQueryPort;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import com.helpnest.global.security.RateLimiter;
import com.helpnest.infra.llm.LlmClient;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI-4 상담 (docs/05 §7). 공개 FAQ 를 근거로만 답하고, 근거가 없거나 LLM 이 실패하면
 * 오류 대신 handoff=true 로 상담원 연결을 안내한다(화면은 한 경로만 처리). 대화는 저장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    static final int FAQ_LIMIT = 3;
    static final int HISTORY_LIMIT = 6;
    static final int HISTORY_ITEM_MAX = 300;
    static final int RATE_LIMIT = 30;
    static final Duration RATE_WINDOW = Duration.ofMinutes(10);

    static final String HANDOFF_ANSWER =
            "이 질문은 상담원이 더 정확하게 안내해 드릴 수 있어요. [상담원 연결]을 눌러 주세요.";

    static final String SYSTEM_PROMPT = """
            너는 HelpNest 고객센터의 AI 상담원이다. 고객이 직접 읽는 답변을 쓴다.
            - [참고 FAQ]에 있는 내용만으로 답한다. FAQ 밖의 정책·금액·날짜·절차는 절대 지어내지 않는다.
            - [참고 FAQ]로 답할 수 없는 질문이면 answer 를 빈 문자열로 둔다(상담원에게 넘긴다).
            - 정중한 존댓말, 2~4문장. 개인 주문·계정 조회가 필요한 질문은 상담원 연결을 권한다.
            응답은 JSON {"answer": "..."} 하나만.
            """;

    private final FaqQueryPort faqQueryPort;
    private final RateLimiter rateLimiter;
    private final LlmClient llmClient;

    /** @throws BusinessException COMMON_TOO_MANY_REQUESTS(429) — 회원당 10분 30회 */
    public AiChatResponse chat(Long memberId, String question, List<Turn> history) {
        if (!rateLimiter.tryAcquire("ai-chat:" + memberId, RATE_LIMIT, RATE_WINDOW)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        List<FaqInfo> faqs = faqQueryPort.findPublishedByQuestion(question, FAQ_LIMIT);
        if (faqs.isEmpty()) {
            return handoff(); // 근거 없음 → LLM 호출 없이 바로 연결 안내
        }
        String answer = callLlm(userPrompt(question, faqs, history == null ? List.of() : history), memberId);
        if (answer == null) {
            return handoff();
        }
        return new AiChatResponse(answer,
                faqs.stream().map(f -> new Reference(f.faqId(), f.question())).toList(), false);
    }

    /** @return 답변, 빈 응답·실패면 null */
    private String callLlm(String userPrompt, Long memberId) {
        try {
            // 재시도 없음: 고객이 기다리므로 실패 시 타임아웃 1회(10초) 뒤 바로 상담원 연결 안내
            AiChatResult r = llmClient.structuredOnce(SYSTEM_PROMPT, userPrompt, AiChatResult.class);
            if (r != null && r.answer() != null && !r.answer().isBlank()) {
                return r.answer().strip();
            }
        } catch (Exception e) {
            log.warn("AI 상담 실패 memberId={} cause={}", memberId, e.toString());
        }
        return null;
    }

    // 질문을 맨 앞에 둔다: SpringAiLlmClient 가 4,000자 뒤를 자르므로 긴 대화가 질문을 밀어내지 않게
    static String userPrompt(String question, List<FaqInfo> faqs, List<Turn> history) {
        StringBuilder sb = new StringBuilder().append("[고객 질문] ").append(question).append('\n')
                .append("[참고 FAQ]\n");
        faqs.forEach(f -> sb.append("- FAQ#").append(f.faqId()).append(": Q ").append(f.question())
                .append(" / A ").append(f.answer()).append('\n'));
        sb.append("[이전 대화]\n");
        history.subList(Math.max(0, history.size() - HISTORY_LIMIT), history.size()).forEach(t -> {
            String c = t.content().strip();
            sb.append("- ").append(t.role()).append(": ")
                    .append(c.length() <= HISTORY_ITEM_MAX ? c : c.substring(0, HISTORY_ITEM_MAX)).append('\n');
        });
        return sb.toString();
    }

    private static AiChatResponse handoff() {
        return new AiChatResponse(HANDOFF_ANSWER, List.of(), true);
    }
}
