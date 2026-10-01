// @owner BSJ
package com.helpnest.domain.member.dto;

import com.helpnest.domain.member.repository.MemberRepository.ConsoleAgentRow;

/**
 * 배정 드롭다운용 상담원 (CR #44). 개인정보(이메일·연락처)는 넣지 않는다.
 *
 * @param activeCount 처리 중인 티켓 수 (ASSIGNED + IN_PROGRESS)
 */
public record ConsoleAgentResponse(Long memberId, String name, boolean available, long activeCount) {

    public static ConsoleAgentResponse from(ConsoleAgentRow row) {
        return new ConsoleAgentResponse(row.getMemberId(), row.getName(), row.isAvailable(), row.getActiveCount());
    }
}
