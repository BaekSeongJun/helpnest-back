// @owner BSJ
package com.helpnest.domain.member.dto;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;

public record MemberResponse(Long memberId, String email, String name, String phone, MemberRole role,
                             boolean available) {

    public static MemberResponse from(Member m) {
        return new MemberResponse(m.getId(), m.getEmail(), m.getName(), m.getPhone(), m.getRole(), m.isAvailable());
    }
}
