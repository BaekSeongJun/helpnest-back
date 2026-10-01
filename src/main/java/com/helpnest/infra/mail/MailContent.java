// @owner SSJ
package com.helpnest.infra.mail;

/** 렌더링된 메일 제목·HTML 본문. MAIL_LOG 에 그대로 저장해 재시도에 쓴다. */
public record MailContent(String subject, String html) {
}
