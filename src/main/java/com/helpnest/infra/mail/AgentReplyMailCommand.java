// @owner SSJ
package com.helpnest.infra.mail;

/**
 * 상담원 답변 알림 메일 (docs/05 §5.1 AGENT_REPLY). 호출자: 박민재.
 * 공개 답변마다 호출하면 된다 — 같은 티켓 10분 내 연속 답변 묶음은 MailSender 가 처리한다.
 */
public record AgentReplyMailCommand(
        Long ticketId,
        String email,
        String ticketNo,
        String replyPreview,
        String ticketUrl) {
}
