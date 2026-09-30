// @owner PMJ
package com.helpnest.domain.ticket.entity;

/**
 * LLM 이 판정한 고객 감정. 값 집합의 단일 권위는 docs/03 §2.1 이다.
 *
 * <p>TICKET.sentiment 는 nullable 이다. {@code null} 은 '중립'이 아니라
 * <b>아직 AI 분류가 수행되지 않았음</b>을 뜻하므로 {@link #NEUTRAL} 과 구별해야 한다.
 */
public enum Sentiment {

    /** 부정. 우선순위 상향 판단의 근거가 된다(docs/05). */
    NEGATIVE,

    /** 중립. 분류를 수행했고 특별한 감정 신호가 없다는 뜻이다. */
    NEUTRAL,

    /** 긍정. */
    POSITIVE
}
