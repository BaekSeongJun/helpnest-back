// @owner SSJ
package com.helpnest.infra.mail;

import java.time.OffsetDateTime;

/**
 * 해결 메일 (docs/05 §5). 호출자: 백성준 SurveyListener.
 * finalReply 는 최종 공개 답변 원문 — 1문장 요약은 MailSender 가 한다(실패 시 앞 200자).
 */
public record ResolvedMailCommand(
        Long ticketId,
        String customerName,
        String email,
        String ticketNo,
        String ticketTitle,
        String finalReply,
        String surveyUrl,
        OffsetDateTime surveyExpiresAt) {
}
