// @owner SSJ
package com.helpnest.infra.mail;

/** 회원가입 이메일 인증 메일. 링크 30분 유효. 호출자: SSJ(이메일 인증) */
public record EmailVerificationMailCommand(
        String email,
        String name,
        String verifyUrl) {
}
