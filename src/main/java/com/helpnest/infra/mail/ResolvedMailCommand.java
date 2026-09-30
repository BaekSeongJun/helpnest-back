// @owner SSJ
package com.helpnest.infra.mail;

import java.time.OffsetDateTime;

/** 해결 메일 (docs/05 §5). 호출자: 백성준 SurveyListener */
public record ResolvedMailCommand(
        Long ticketId,
        String customerName,
        String email,
        String ticketNo,
        String ticketTitle,
        String replySummary,
        String surveyUrl,
        OffsetDateTime surveyExpiresAt) {
}
