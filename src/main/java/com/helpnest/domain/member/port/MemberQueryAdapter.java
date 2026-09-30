// @owner BSJ
package com.helpnest.domain.member.port;

import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryAdapter implements MemberQueryPort {

    private final MemberRepository memberRepository;

    @Override
    public List<MemberInfo> findAssignableAgents() {
        return memberRepository.findByRoleAndStatusAndAvailableTrue(MemberRole.AGENT, MemberStatus.ACTIVE)
                .stream().map(MemberInfo::from).toList();
    }

    @Override
    public MemberInfo getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .map(MemberInfo::from)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND));
    }

    @Override
    @Transactional
    public void touchLastAssigned(Long agentId) {
        memberRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND))
                .touchLastAssigned(OffsetDateTime.now());
    }
}
