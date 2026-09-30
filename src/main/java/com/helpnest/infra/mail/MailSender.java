// @owner SSJ
package com.helpnest.infra.mail;

/** 호출자: 백성준(해결·비밀번호 재설정), 박민재(답변 알림) (docs/02 §5.2) */
public interface MailSender {

    void sendResolvedMail(ResolvedMailCommand command);

    void sendAgentReplyMail(AgentReplyMailCommand command);

    void sendPasswordResetMail(PasswordResetMailCommand command);
}
