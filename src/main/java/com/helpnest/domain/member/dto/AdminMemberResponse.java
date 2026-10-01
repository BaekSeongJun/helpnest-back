// @owner BSJ
package com.helpnest.domain.member.dto;

import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import java.time.OffsetDateTime;

/** 관리자 계정 목록·변경 응답 (AD-01 표: 이메일, 이름, 역할, 상태, 가입일) */
public record AdminMemberResponse(Long memberId, String email, String name, String phone, MemberRole role,
                                  MemberStatus status, boolean available, OffsetDateTime createdAt) {

    public static AdminMemberResponse from(Member m) {
        return new AdminMemberResponse(m.getId(), m.getEmail(), m.getName(), m.getPhone(), m.getRole(),
                m.getStatus(), m.isAvailable(), m.getCreatedAt());
    }
}
