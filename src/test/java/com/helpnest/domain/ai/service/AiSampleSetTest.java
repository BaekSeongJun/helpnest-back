// @owner SSJ
package com.helpnest.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 정확도 측정 샘플셋 구성 검사 — LLM 없이 항상 실행 (docs/05 §6) */
class AiSampleSetTest {

    @Test
    @DisplayName("30건, 유형별 4~5건, 불만 10건, 빈 값 없음")
    void shape() throws Exception {
        AiSample[] samples;
        try (InputStream in = getClass().getResourceAsStream("/ai/samples.json")) {
            samples = JsonMapper.builder().build().readValue(in, AiSample[].class);
        }

        assertThat(samples).hasSize(30).allSatisfy(s -> {
            assertThat(s.title()).isNotBlank();
            assertThat(s.content()).isNotBlank();
            assertThat(s.expectedCategory()).isNotNull();
            assertThat(s.expectedSentiment()).isNotNull();
        });
        for (Category c : Category.values()) {
            assertThat(Arrays.stream(samples).filter(s -> s.expectedCategory() == c).count())
                    .as(c.name()).isBetween(4L, 5L);
        }
        assertThat(Arrays.stream(samples).filter(s -> s.expectedSentiment() == Sentiment.NEGATIVE).count())
                .isEqualTo(10L);
    }
}
