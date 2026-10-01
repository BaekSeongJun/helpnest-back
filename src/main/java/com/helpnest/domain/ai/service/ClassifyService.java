// @owner SSJ
package com.helpnest.domain.ai.service;

import com.helpnest.domain.ai.dto.ClassificationResult;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import com.helpnest.domain.ai.entity.TicketAiResult;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ticket.port.TicketClassificationPort;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.domain.ticket.port.TicketSummary;
import com.helpnest.infra.llm.LlmClient;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI-1 분류 (docs/05 §3.1). 리스너(비동기)와 재분류 API(동기)가 함께 쓴다.
 * LLM 호출은 최대 ~20초라 트랜잭션 밖에서 하고, 결과 저장만 리포지토리 트랜잭션으로 짧게 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    static final String SYSTEM_PROMPT = """
            너는 한국어 고객상담 티켓 분류기다. 고객 문의를 읽고 반드시 아래 JSON 형식으로만 답한다.
            - category: DELIVERY(배송), REFUND(환불), EXCHANGE(교환), PAYMENT(결제), ACCOUNT(계정/로그인), SERVICE_ERROR(서비스 오류), ETC(기타) 중 하나
            - urgency: URGENT(금전 손실·서비스 전체 장애·법적 언급), HIGH(업무/사용 불가), NORMAL(일반 문의), LOW(단순 정보 요청·제안) 중 하나
            - sentiment: NEGATIVE(불만·분노·재문의 언급), NEUTRAL, POSITIVE 중 하나
            - summary: 상담원이 한눈에 볼 수 있는 한 문장 요약(60자 이내)
            - confidence: 0.0 ~ 1.0
            고객이 선택한 유형은 참고만 하고 본문 내용을 우선한다.
            """;

    private static final int SUMMARY_MAX = 500; // ticket_ai_result.summary VARCHAR(500)

    private final TicketQueryPort ticketQueryPort;
    private final TicketClassificationPort ticketClassificationPort;
    private final TicketAiResultRepository repository;
    private final LlmClient llmClient;
    private final JsonMapper jsonMapper;

    /** 분류 후 티켓에 반영. 실패해도 예외를 던지지 않고 FAILED 결과를 돌려준다 */
    public TicketAiResult classify(Long ticketId) {
        // 티켓이 없으면 포트가 예외 → 호출자(API)에서 처리. 분류 실패와는 구분한다
        TicketSummary ticket = ticketQueryPort.getTicketSummary(ticketId);
        long start = System.currentTimeMillis();
        try {
            ClassificationResult r = llmClient.structured(SYSTEM_PROMPT, userPrompt(ticket), ClassificationResult.class);
            validate(r);
            TicketAiResult saved = saveSuccess(ticketId, r, elapsed(start));
            ticketClassificationPort.applyClassification(ticketId, r.category().name(),
                    priority(r.urgency(), r.sentiment()).name(), r.sentiment().name());
            return saved;
        } catch (Exception e) {
            // LLM 실패·응답 누락뿐 아니라 포트가 값을 거부한 경우도 기본값 배정으로 넘겨 티켓이 방치되지 않게 한다
            log.warn("AI 분류 실패 ticketId={} cause={}", ticketId, e.toString());
            TicketAiResult saved = saveFailed(ticketId, elapsed(start));
            ticketClassificationPort.applyClassificationFailed(ticketId);
            return saved;
        }
    }

    /** urgency 기준, NEGATIVE 면 1단계 상향(최대 URGENT) (docs/05 §3.1 5번). 이름이 ticket priority 값과 같다 */
    static Urgency priority(Urgency urgency, Sentiment sentiment) {
        if (sentiment != Sentiment.NEGATIVE || urgency == Urgency.URGENT) {
            return urgency;
        }
        // enum 선언 순서가 URGENT, HIGH, NORMAL, LOW 라 앞 칸이 한 단계 위
        return Urgency.values()[urgency.ordinal() - 1];
    }

    static String userPrompt(TicketSummary t) {
        return "[고객 선택 유형] " + t.categoryHint() + "\n[제목] " + t.title() + "\n[본문] " + t.content();
    }

    private static void validate(ClassificationResult r) {
        if (r == null || r.category() == null || r.urgency() == null || r.sentiment() == null) {
            throw new IllegalStateException("분류 응답 누락: " + r);
        }
    }

    private TicketAiResult saveSuccess(Long ticketId, ClassificationResult r, int latencyMs) {
        String summary = r.summary() == null || r.summary().length() <= SUMMARY_MAX
                ? r.summary() : r.summary().substring(0, SUMMARY_MAX);
        // NUMERIC(3,2): 0.00 ~ 9.99 범위라 0~1 로 자르고 소수 2자리
        BigDecimal confidence = BigDecimal.valueOf(Math.clamp(r.confidence(), 0.0, 1.0))
                .setScale(2, RoundingMode.HALF_UP);
        String raw = jsonMapper.writeValueAsString(r);
        String model = llmClient.modelName();

        TicketAiResult entity = repository.findByTicketId(ticketId)
                .map(existing -> {
                    existing.updateSuccess(r.category(), r.urgency(), r.sentiment(), summary, confidence,
                            model, raw, latencyMs);
                    return existing;
                })
                .orElseGet(() -> TicketAiResult.builder()
                        .ticketId(ticketId).category(r.category()).urgency(r.urgency()).sentiment(r.sentiment())
                        .summary(summary).confidence(confidence).status(TicketAiResult.Status.SUCCESS)
                        .model(model).rawResponse(raw).latencyMs(latencyMs).build());
        return repository.save(entity);
    }

    private TicketAiResult saveFailed(Long ticketId, int latencyMs) {
        String model = llmClient.modelName();
        TicketAiResult entity = repository.findByTicketId(ticketId)
                .map(existing -> {
                    existing.markFailed(model, latencyMs);
                    return existing;
                })
                .orElseGet(() -> TicketAiResult.builder()
                        .ticketId(ticketId).status(TicketAiResult.Status.FAILED)
                        .model(model).latencyMs(latencyMs).build());
        return repository.save(entity);
    }

    private static int elapsed(long start) {
        return (int) (System.currentTimeMillis() - start);
    }
}
