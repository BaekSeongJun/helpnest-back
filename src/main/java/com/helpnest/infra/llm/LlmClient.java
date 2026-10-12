// @owner SSJ
package com.helpnest.infra.llm;

/** 호출자: domain/ai(ClassifyService·DraftService·AiChatService) (docs/05 §2) */
public interface LlmClient {

    /** system·user 프롬프트로 호출해 응답 JSON 을 type 으로 변환 */
    <T> T structured(String system, String user, Class<T> type);

    /** 재시도 없이 1회만. 고객이 기다리는 동기 호출(AI 상담)용 — 실패 시 대기가 타임아웃 1회로 끝난다 */
    default <T> T structuredOnce(String system, String user, Class<T> type) {
        return structured(system, user, type);
    }

    /** TICKET_AI_RESULT.model·AI_DRAFT.model 기록용 */
    String modelName();
}
