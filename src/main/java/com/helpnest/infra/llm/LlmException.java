// @owner SSJ
package com.helpnest.infra.llm;

/** LLM 호출 최종 실패(타임아웃·재시도 소진·응답 변환 실패). 호출자는 fallback 처리한다 (docs/05 §3.1 7번) */
public class LlmException extends RuntimeException {

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
