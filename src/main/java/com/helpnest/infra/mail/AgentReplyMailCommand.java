// @owner SSJ
package com.helpnest.infra.mail;

/** 상담원 답변 알림 메일 (docs/05 §5.1 AGENT_REPLY). 호출자: 박민재 */
public record AgentReplyMailCommand(
        Long ticketId,
        String email,
        String ticketNo,
        String replyPreview,
        String ticketUrl) {
}
