// @owner BSJ
package com.helpnest.domain.member.service;

import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.dto.ConsoleAgentResponse;
import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.dto.PasswordChangeRequest;
import com.helpnest.domain.member.dto.ProfileUpdateRequest;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.event.AgentAvailabilityChangedEvent;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public MemberResponse getMe(Long memberId) {
        return memberRepository.findById(memberId)
                .map(MemberResponse::from)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND));
    }

    @Transactional
    public MemberResponse updateProfile(Long memberId, ProfileUpdateRequest req) {
        Member member = findOrThrow(memberId);
        member.changeProfile(req.name().trim(), StringUtils.hasText(req.phone()) ? req.phone() : null);
        return MemberResponse.from(member);
    }

    /** 현재 비밀번호 확인 후 교체 + Refresh 전부 폐기 (FR-AUTH-08) */
    @Transactional
    public void changePassword(Long memberId, PasswordChangeRequest req) {
        Member member = findOrThrow(memberId);
        if (!passwordEncoder.matches(req.currentPassword(), member.getPassword())) {
            throw new BusinessException(AuthErrorCode.PASSWORD_MISMATCH);
        }
        member.changePassword(passwordEncoder.encode(req.newPassword()));
        refreshTokenRepository.revokeAllByMemberId(memberId);
    }

    /**
     * 회원 탈퇴 = 비활성화 + Refresh 전부 폐기. 로그인·재발급·소셜 로그인이 모두 AUTH_INACTIVE_MEMBER 로 막힌다.
     * ponytail: 개인정보와 이메일 점유는 그대로 남는다(같은 이메일 재가입 불가, 복구는 ADMIN 이 활성화).
     * 재가입·개인정보 파기가 필요해지면 이메일·이름·연락처 익명화 + member_social 삭제로 바꾼다.
     * 이미 발급된 Access 토큰은 만료(30분)까지 유효 — AdminMemberService 의 비활성화와 같은 한계
     */
    @Transactional
    public void withdraw(Long memberId) {
        // 벌크 update 가 영속성 컨텍스트를 비우므로 폐기 뒤에 읽는다
        refreshTokenRepository.revokeAllByMemberId(memberId);
        findOrThrow(memberId).changeStatus(MemberStatus.INACTIVE);
    }

    private Member findOrThrow(Long memberId) {
        return memberRepository.findById(memberId)
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
        // 값이 실제로 바뀐 경우만 알린다 — 같은 값 재토글로 대기열이 헛돌지 않게 (CR #90)
        if (member.isAvailable() != available) {
            member.changeAvailable(available);
            eventPublisher.publishEvent(new AgentAvailabilityChangedEvent(memberId, available));
        }
        return MemberResponse.from(member);
    }

    /** 배정 드롭다운용 활성 상담원 + 처리 중 건수 (CR #44) */
    public List<ConsoleAgentResponse> findConsoleAgents() {
        return memberRepository.findConsoleAgents().stream().map(ConsoleAgentResponse::from).toList();
    }
}
