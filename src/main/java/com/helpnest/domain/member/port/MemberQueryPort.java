// @owner BSJ
package com.helpnest.domain.member.port;

import java.util.List;

/** 호출자: 박민재(배정·SLA), 신수진 (docs/02 §5.2) */
public interface MemberQueryPort {

    /** 자동 배정 대상: role=AGENT, status=ACTIVE, available=true. 부하 계산은 호출자가 한다. */
    List<MemberInfo> findAssignableAgents();

    /** 없으면 MEMBER_NOT_FOUND */
    MemberInfo getMember(Long memberId);

    /** 배정 직후 호출 — last_assigned_at 을 현재 시각으로 갱신 */
    void touchLastAssigned(Long agentId);
}
