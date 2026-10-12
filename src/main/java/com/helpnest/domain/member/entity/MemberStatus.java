// @owner BSJ
package com.helpnest.domain.member.entity;

public enum MemberStatus {
    ACTIVE, INACTIVE,
    /** 가입 후 이메일 인증 전. 로그인할 수 없다 */
    PENDING
}
