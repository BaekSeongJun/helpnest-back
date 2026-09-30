// @owner BSJ
package com.helpnest.domain.member.port;

import com.helpnest.domain.member.entity.Member;
import java.time.OffsetDateTime;

/** 다른 도메인에 넘기는 회원 정보. 비밀번호 등 민감 정보는 담지 않는다. */
public record MemberInfo(
        Long memberId,
        String email,
        String name,
        String role,
        boolean available,
        OffsetDateTime lastAssignedAt) {

    public static MemberInfo from(Member m) {
        return new MemberInfo(m.getId(), m.getEmail(), m.getName(), m.getRole().name(),
                m.isAvailable(), m.getLastAssignedAt());
    }
}
