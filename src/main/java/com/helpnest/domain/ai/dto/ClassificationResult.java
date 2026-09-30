// @owner SSJ
package com.helpnest.domain.ai.dto;

/** AI-1 분류 결과. docs/05 §3.2 출력 JSON 과 같은 필드·enum 값 */
public record ClassificationResult(
        Category category,
        Urgency urgency,
        Sentiment sentiment,
        String summary,
        double confidence) {

    public enum Category { DELIVERY, REFUND, EXCHANGE, PAYMENT, ACCOUNT, SERVICE_ERROR, ETC }

    public enum Urgency { URGENT, HIGH, NORMAL, LOW }

    public enum Sentiment { NEGATIVE, NEUTRAL, POSITIVE }
}
