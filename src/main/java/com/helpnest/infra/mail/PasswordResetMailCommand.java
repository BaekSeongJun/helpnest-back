// @owner SSJ
package com.helpnest.infra.mail;

/** 비밀번호 재설정 메일 (docs/05 §5.1). guest=true 면 GUEST_PASSWORD_RESET. 호출자: 백성준 */
public record PasswordResetMailCommand(
        String email,
        String resetUrl,
        boolean guest) {
}
