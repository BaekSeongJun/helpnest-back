// @owner BSJ
package com.helpnest.domain.member.service;

import com.helpnest.domain.member.dto.ConsoleAgentResponse;
import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberResponse getMe(Long memberId) {
        return memberRepository.findById(memberId)
                .map(MemberResponse::from)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND));
    }

    /** 자동 배정 대상 여부. 역할 계층상 LEAD·ADMIN 도 hasRole('AGENT') 를 통과하므로 DB 역할로 확인 */
    @Transactional
    public MemberResponse updateAvailability(Long memberId, boolean available) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND));
        if (member.getRole() != MemberRole.AGENT) {
            throw new BusinessException(MemberErrorCode.NOT_AGENT);
        }
        member.changeAvailable(available);
        return MemberResponse.from(member);
    }

    /** 배정 드롭다운용 활성 상담원 + 처리 중 건수 (CR #44) */
    public List<ConsoleAgentResponse> findConsoleAgents() {
        return memberRepository.findConsoleAgents().stream().map(ConsoleAgentResponse::from).toList();
    }
}
