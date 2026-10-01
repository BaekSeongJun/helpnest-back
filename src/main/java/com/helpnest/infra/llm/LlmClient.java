// @owner SSJ
package com.helpnest.infra.llm;

/** 호출자: domain/ai(ClassifyService·DraftService) (docs/05 §2) */
public interface LlmClient {

    /** system·user 프롬프트로 호출해 응답 JSON 을 type 으로 변환 */
    <T> T structured(String system, String user, Class<T> type);

    /** TICKET_AI_RESULT.model·AI_DRAFT.model 기록용 */
    String modelName();
}
