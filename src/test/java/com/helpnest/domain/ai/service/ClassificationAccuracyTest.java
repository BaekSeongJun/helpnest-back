// @owner SSJ
package com.helpnest.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpnest.domain.ai.dto.ClassificationResult;
import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ticket.port.TicketSummary;
import com.helpnest.infra.llm.LlmClient;
import com.helpnest.infra.llm.SpringAiLlmClient;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 LLM 분류 정확도 측정 (docs/05 §6). 호출 비용이 있어 평소 verify 에서는 건너뛴다.
 * 실행: SPRING_PROFILES_ACTIVE=local LLM_PROVIDER=google-genai AI_ACCURACY=true
 *       ./mvnw test -Dtest=ClassificationAccuracyTest
 * 무료 등급 분당 한도에 걸리면 AI_ACCURACY_DELAY_MS(호출 간격)를 준다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "AI_ACCURACY", matches = "true")
class ClassificationAccuracyTest {

    @Autowired
    LlmClient llmClient;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    @DisplayName("유형 정확도 80% 이상, 불만 재현율 80% 이상")
    void accuracy() throws Exception {
        assertThat(llmClient).as("LLM_PROVIDER=google-genai 필요").isInstanceOf(SpringAiLlmClient.class);
        List<AiSample> samples;
        try (InputStream in = getClass().getResourceAsStream("/ai/samples.json")) {
            samples = List.of(jsonMapper.readValue(in, AiSample[].class));
        }
        long delayMs = Long.parseLong(System.getenv().getOrDefault("AI_ACCURACY_DELAY_MS", "0"));

        int categoryHit = 0;
        int negativeTotal = 0;
        int negativeHit = 0;
        List<String> misses = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            AiSample s = samples.get(i);
            ClassificationResult r = classify(s);
            boolean categoryOk = r != null && r.category() == s.expectedCategory();
            if (categoryOk) {
                categoryHit++;
            }
            if (s.expectedSentiment() == Sentiment.NEGATIVE) {
                negativeTotal++;
                if (r != null && r.sentiment() == Sentiment.NEGATIVE) {
                    negativeHit++;
                }
            }
            if (!categoryOk || r.sentiment() != s.expectedSentiment()) {
                misses.add("#%d %s → 기대 %s/%s, 결과 %s".formatted(i + 1, s.title(), s.expectedCategory(),
                        s.expectedSentiment(), r == null ? "실패" : r.category() + "/" + r.sentiment()));
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }

        double categoryAccuracy = (double) categoryHit / samples.size();
        double negativeRecall = (double) negativeHit / negativeTotal;
        System.out.printf("%n[AI 정확도] 모델=%s 샘플=%d%n유형 정확도 %d/%d = %.1f%%%n불만 재현율 %d/%d = %.1f%%%n",
                llmClient.modelName(), samples.size(), categoryHit, samples.size(), categoryAccuracy * 100,
                negativeHit, negativeTotal, negativeRecall * 100);
        misses.forEach(m -> System.out.println("  - " + m));

        assertThat(categoryAccuracy).as("유형 정확도").isGreaterThanOrEqualTo(0.8);
        assertThat(negativeRecall).as("불만 재현율").isGreaterThanOrEqualTo(0.8);
    }

    // 운영과 같은 프롬프트(ClassifyService)로 호출. 실패(타임아웃 등)는 오답으로 센다
    private ClassificationResult classify(AiSample s) {
        try {
            return llmClient.structured(ClassifyService.SYSTEM_PROMPT,
                    ClassifyService.userPrompt(new TicketSummary(s.title(), s.content(), s.categoryHint())),
                    ClassificationResult.class);
        } catch (RuntimeException e) {
            System.out.println("  ! 호출 실패 " + s.title() + ": " + e.getMessage());
            return null;
        }
    }

}
