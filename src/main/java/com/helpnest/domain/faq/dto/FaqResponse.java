// @owner BSJ
package com.helpnest.domain.faq.dto;

import com.helpnest.domain.faq.entity.Faq;
import com.helpnest.domain.ticket.entity.TicketCategory;
import java.time.OffsetDateTime;

public record FaqResponse(Long faqId, TicketCategory category, String question, String answer, boolean published,
                          int viewCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static FaqResponse from(Faq f) {
        return new FaqResponse(f.getId(), f.getCategory(), f.getQuestion(), f.getAnswer(), f.isPublished(),
                f.getViewCount(), f.getCreatedAt(), f.getUpdatedAt());
    }
}
