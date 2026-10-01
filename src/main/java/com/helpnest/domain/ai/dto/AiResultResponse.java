// @owner SSJ
package com.helpnest.domain.ai.dto;

import com.helpnest.domain.ai.dto.ClassificationResult.Category;
import com.helpnest.domain.ai.dto.ClassificationResult.Sentiment;
import com.helpnest.domain.ai.dto.ClassificationResult.Urgency;
import com.helpnest.domain.ai.entity.TicketAiResult;
import java.math.BigDecimal;

/** 분류 결과 (docs/04 §12). FAILED 면 분류값은 null */
public record AiResultResponse(
        Category category,
        Urgency urgency,
        Sentiment sentiment,
        String summary,
        BigDecimal confidence,
        TicketAiResult.Status status) {

    public static AiResultResponse from(TicketAiResult r) {
        return new AiResultResponse(r.getCategory(), r.getUrgency(), r.getSentiment(), r.getSummary(),
                r.getConfidence(), r.getStatus());
    }
}
