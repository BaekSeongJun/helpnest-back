// @owner BSJ
package com.helpnest.domain.template.dto;

import com.helpnest.domain.template.entity.Template;
import com.helpnest.domain.ticket.entity.TicketCategory;
import java.time.OffsetDateTime;

public record TemplateResponse(Long templateId, TicketCategory category, String title, String content, boolean active,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static TemplateResponse from(Template t) {
        return new TemplateResponse(t.getId(), t.getCategory(), t.getTitle(), t.getContent(), t.isActive(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
