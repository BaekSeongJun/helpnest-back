// @owner BSJ
package com.helpnest.domain.auth.oauth;

import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 소셜 프로필 → 회원 (FR-AUTH-11). 찾는 순서:
 * <ol>
 *   <li>이미 연결된 소셜 계정이면 그 회원.</li>
 *   <li>아니면 제공자가 <b>본인 것으로 확인한</b> 이메일로 기존 회원을 찾아 연결하거나, 없으면 CUSTOMER 로 가입.</li>
 * </ol>
 * 이메일을 확인해 주지 않은 프로필은 받지 않는다 — 확인되지 않은 이메일로 연결하면 남의 계정에 들어갈 수 있다.
 */
@Service
@RequiredArgsConstructor
public class OAuthMemberService {

    private final MemberSocialRepository socialRepository;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public Member resolve(OAuthProfile profile) {
        if (!StringUtils.hasText(profile.providerUserId())) {
            throw new BusinessException(AuthErrorCode.OAUTH_FAILED);
        }
        Member member = socialRepository
                .findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .flatMap(s -> memberRepository.findById(s.getMemberId()))
                .orElseGet(() -> link(profile));
        if (member.getStatus() == MemberStatus.INACTIVE) {
            throw new BusinessException(AuthErrorCode.INACTIVE_MEMBER);
        }
        return member;
    }

    private Member link(OAuthProfile profile) {
        if (!StringUtils.hasText(profile.email()) || !profile.emailVerified()) {
            throw new BusinessException(AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        }
        Optional<Member> existing = memberRepository.findByEmail(profile.email());
        // 직원 계정은 ADMIN 이 만든 비밀번호 계정으로만 — 소셜 계정이 뚫려도 콘솔에 들어오지 못하게
        if (existing.filter(m -> m.getRole() != MemberRole.CUSTOMER).isPresent()) {
            throw new BusinessException(AuthErrorCode.OAUTH_STAFF_NOT_ALLOWED);
        }
        Member member = existing.map(this::takeOverIfUnverified).orElseGet(() -> memberRepository.save(
                Member.builder()
                        .email(profile.email())
                        .name(StringUtils.hasText(profile.name()) ? profile.name() : profile.email().split("@")[0])
                        .role(MemberRole.CUSTOMER)
                        .build()));
        member.verifyEmail(OffsetDateTime.now());
        socialRepository.save(new MemberSocial(member.getId(), profile.provider(), profile.providerUserId()));
        return member;
    }

    /**
     * 이메일을 인증한 적 없는 기존 계정(인증 전 PENDING, 인증 도입 전 가입자)은 그 이메일의 주인이 만든 것인지 알 수 없다.
     * 남이 내 이메일로 먼저 가입해 두었을 수 있으므로, 연결할 때 비밀번호와 로그인 세션을 지운다 — 지우지 않으면
     * 그 사람이 자기가 정한 비밀번호로 내 계정에 계속 들어올 수 있다. 진짜 주인은 소셜 로그인이나 "비밀번호 찾기"로 들어온다.
     */
    private Member takeOverIfUnverified(Member member) {
        if (member.getEmailVerifiedAt() != null) {
            return member;
        }
        // 벌크 update 가 영속성 컨텍스트를 비우므로 폐기 뒤에 다시 읽는다
        refreshTokenRepository.revokeAllByMemberId(member.getId());
        Member fresh = memberRepository.findById(member.getId()).orElseThrow();
        fresh.changePassword(null);
        return fresh;
    }
}
