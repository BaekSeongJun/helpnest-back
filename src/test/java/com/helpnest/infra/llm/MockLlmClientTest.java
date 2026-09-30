// @owner SSJ
package com.helpnest.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helpnest.domain.ai.dto.ClassificationResult;
import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MockLlmClientTest {

    private final MockLlmClient client = new MockLlmClient();

    @Test
    @DisplayName("불만 키워드 → NEGATIVE, 카테고리 우선순위(환불 > 결제)")
    void negative() {
        var result = client.structured("sys", "[제목] 결제 환불\n[본문] 환불이 안돼서 너무 불만입니다", ClassificationResult.class);

        assertThat(result.category()).isEqualTo(Category.REFUND);
        assertThat(result.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(result.sentiment()).isEqualTo(Sentiment.NEGATIVE);
    }

    @Test
    @DisplayName("매칭 없음 → ETC / NORMAL / NEUTRAL, 요약 60자 이내")
    void noMatch() {
        var result = client.structured("sys", "문의 ".repeat(40), ClassificationResult.class);

        assertThat(result.category()).isEqualTo(Category.ETC);
        assertThat(result.urgency()).isEqualTo(Urgency.NORMAL);
        assertThat(result.sentiment()).isEqualTo(Sentiment.NEUTRAL);
        assertThat(result.summary()).hasSizeLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("분류 외 타입은 미지원")
    void unsupportedType() {
        assertThatThrownBy(() -> client.structured("sys", "user", String.class))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("llm.provider 미설정·mock 이면 빈 등록, 다른 제공자면 미등록")
    void conditionalBean() {
        var runner = new ApplicationContextRunner().withUserConfiguration(MockLlmClient.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(LlmClient.class));
        runner.withPropertyValues("llm.provider=mock").run(ctx -> assertThat(ctx).hasSingleBean(LlmClient.class));
        runner.withPropertyValues("llm.provider=openai").run(ctx -> assertThat(ctx).doesNotHaveBean(LlmClient.class));
    }
}
