// @owner BSJ
package com.helpnest.domain.member.dto;

import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;

/** 역할·상태 변경. null 인 항목은 그대로 둔다 */
public record AdminMemberUpdateRequest(MemberRole role, MemberStatus status) {
}
