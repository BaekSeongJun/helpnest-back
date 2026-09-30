// @owner SSJ
package com.helpnest.infra.llm;

import com.helpnest.domain.ai.dto.ClassificationResult;
import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 키 없이 로컬 개발용 (LLM_PROVIDER 미설정 또는 mock).
 * 키워드 규칙이라 분류 정확도 측정(S1)과는 무관하다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    // 앞에 있을수록 우선 (예: "결제 환불" → REFUND)
    private static final List<Map.Entry<Category, List<String>>> CATEGORY_RULES = List.of(
            Map.entry(Category.REFUND, List.of("환불")),
            Map.entry(Category.EXCHANGE, List.of("교환")),
            Map.entry(Category.PAYMENT, List.of("결제", "카드", "청구")),
            Map.entry(Category.DELIVERY, List.of("배송", "택배", "도착")),
            Map.entry(Category.ACCOUNT, List.of("로그인", "계정", "비밀번호")),
            Map.entry(Category.SERVICE_ERROR, List.of("오류", "에러", "장애")));

    private static final List<String> URGENT_WORDS = List.of("긴급", "장애", "소송", "법적");
    private static final List<String> HIGH_WORDS = List.of("안 돼", "안돼", "불가");
    private static final List<String> NEGATIVE_WORDS = List.of("불만", "화가", "짜증", "재문의", "다시 문의");
    private static final List<String> POSITIVE_WORDS = List.of("감사", "고맙");

    @Override
    public <T> T structured(String system, String user, Class<T> type) {
        if (type != ClassificationResult.class) {
            throw new UnsupportedOperationException("MockLlmClient 미지원 타입: " + type.getSimpleName());
        }
        String text = user == null ? "" : user;
        return type.cast(new ClassificationResult(category(text), urgency(text), sentiment(text),
                summary(text), 0.5));
    }

    private static Category category(String text) {
        return CATEGORY_RULES.stream()
                .filter(rule -> containsAny(text, rule.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(Category.ETC);
    }

    private static Urgency urgency(String text) {
        if (containsAny(text, URGENT_WORDS)) {
            return Urgency.URGENT;
        }
        return containsAny(text, HIGH_WORDS) ? Urgency.HIGH : Urgency.NORMAL;
    }

    private static Sentiment sentiment(String text) {
        if (containsAny(text, NEGATIVE_WORDS)) {
            return Sentiment.NEGATIVE;
        }
        return containsAny(text, POSITIVE_WORDS) ? Sentiment.POSITIVE : Sentiment.NEUTRAL;
    }

    // 요약은 60자 이내 (docs/05 §3.2)
    private static String summary(String text) {
        String oneLine = text.replaceAll("\s+", " ").strip();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60);
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }
}
