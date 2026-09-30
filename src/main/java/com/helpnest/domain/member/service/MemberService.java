// @owner BSJ
package com.helpnest.domain.member.service;

import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
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
}
