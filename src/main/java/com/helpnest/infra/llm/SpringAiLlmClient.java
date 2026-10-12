// @owner SSJ
package com.helpnest.infra.llm;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Spring AI ChatClient 기반 구현 (LLM_PROVIDER=google-genai 등, mock 이 아닐 때).
 * 타임아웃 10초, 재시도 1회(AI 상담은 {@link #structuredOnce} 로 재시도 없음), 입력 4,000자, 전송 전 마스킹 (docs/05 §2).
 */
@Slf4j
@Component
@ConditionalOnExpression("'${llm.provider:mock}' != 'mock'")
public class SpringAiLlmClient implements LlmClient {

    static final int MAX_INPUT = 4_000;
    private static final long TIMEOUT_SECONDS = 10;
    private static final int MAX_ATTEMPTS = 2; // 최초 1회 + 재시도 1회

    private final ChatClient chatClient;
    private final String modelName;
    // ponytail: 타임아웃 시 cancel 은 인터럽트만 보내고 진행 중 HTTP 요청은 끝까지 갈 수 있다. 가상 스레드라 비용은 작음
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpringAiLlmClient(ChatClient.Builder builder,
            @Value("${llm.provider}") String provider,
            @Value("${llm.model:}") String model) {
        this.chatClient = builder.build();
        this.modelName = model.isBlank() ? provider : model;
    }

    @Override
    public <T> T structured(String system, String user, Class<T> type) {
        return call(system, user, type, MAX_ATTEMPTS);
    }

    @Override
    public <T> T structuredOnce(String system, String user, Class<T> type) {
        return call(system, user, type, 1);
    }

    private <T> T call(String system, String user, Class<T> type, int maxAttempts) {
        // user 에 템플릿 변수를 넘기지 않으므로 본문의 { } 는 렌더링되지 않는다
        String safeUser = PiiMasker.mask(truncate(user));
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<T> future = executor.submit(
                    () -> chatClient.prompt().system(system).user(safeUser).call().entity(type));
            try {
                return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException e) {
                future.cancel(true);
                last = e;
                log.warn("LLM 호출 실패 attempt={}/{} type={} cause={}", attempt, maxAttempts,
                        type.getSimpleName(), e.toString());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new LlmException("LLM 호출 중단", e);
            }
        }
        throw new LlmException(maxAttempts > 1 ? "LLM 호출 실패(재시도 소진)" : "LLM 호출 실패", last);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    // 초과 시 앞부분 사용
    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_INPUT ? text : text.substring(0, MAX_INPUT);
    }
}
