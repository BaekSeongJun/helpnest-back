// @owner SSJ
package com.helpnest.infra.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SpringAiLlmClientTest {

    record Answer(String value) {
    }

    private static ChatResponse reply(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    // ChatClient 가 getOptions().mutate() 를 호출하므로 기본 옵션을 돌려줘야 한다
    private static ChatModel mockModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        return model;
    }

    private static SpringAiLlmClient client(ChatModel model) {
        return new SpringAiLlmClient(ChatClient.builder(model), "google-genai", "");
    }

    @Test
    @DisplayName("전송 전 마스킹 + JSON 응답을 타입으로 변환")
    void masksAndConverts() {
        ChatModel model = mockModel();
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"value\":\"ok\"}"));

        Answer answer = client(model).structured("sys", "연락처 010-1234-5678 {x}", Answer.class);

        assertThat(answer.value()).isEqualTo("ok");
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        String sent = captor.getValue().getUserMessage().getText();
        assertThat(sent).contains("010-****-5678").doesNotContain("1234-5678").contains("{x}");
    }

    @Test
    @DisplayName("1회 실패 후 재시도로 성공")
    void retriesOnce() {
        ChatModel model = mockModel();
        when(model.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("503"))
                .thenReturn(reply("{\"value\":\"ok\"}"));

        assertThat(client(model).structured("sys", "u", Answer.class).value()).isEqualTo("ok");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("2회 모두 실패하면 LlmException")
    void failsAfterRetry() {
        ChatModel model = mockModel();
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("503"));

        assertThatThrownBy(() -> client(model).structured("sys", "u", Answer.class))
                .isInstanceOf(LlmException.class);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("4,000자 초과 입력은 앞부분만, 모델명은 llm.model 우선")
    void truncateAndModelName() {
        assertThat(SpringAiLlmClient.truncate("가".repeat(5000))).hasSize(SpringAiLlmClient.MAX_INPUT);
        assertThat(client(mockModel()).modelName()).isEqualTo("google-genai");
        assertThat(new SpringAiLlmClient(ChatClient.builder(mockModel()), "google-genai", "gemini-2.5-flash")
                .modelName()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    @DisplayName("llm.provider 미설정·mock 이면 Mock 만, 그 외면 SpringAi 만 등록")
    void mutuallyExclusive() {
        var runner = new ApplicationContextRunner()
                .withBean(ChatClient.Builder.class, () -> ChatClient.builder(mockModel()))
                .withUserConfiguration(MockLlmClient.class, SpringAiLlmClient.class);

        runner.run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(MockLlmClient.class));
        runner.withPropertyValues("llm.provider=mock")
                .run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(MockLlmClient.class));
        runner.withPropertyValues("llm.provider=google-genai")
                .run(ctx -> assertThat(ctx).getBean(LlmClient.class).isInstanceOf(SpringAiLlmClient.class));
    }
}
