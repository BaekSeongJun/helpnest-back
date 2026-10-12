// @owner BSJ
package com.helpnest.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 소셜 프로필 → 회원 연결 규칙 (FR-AUTH-11). 계정 탈취로 이어질 수 있는 분기를 고정한다 */
class OAuthMemberServiceTest {

    private static final String EMAIL = "social@helpnest.local";
    private static final OAuthProfile VERIFIED = new OAuthProfile(OAuthProvider.GOOGLE, "g-1", EMAIL, true, "홍길동");

    private final MemberSocialRepository socialRepository = mock(MemberSocialRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final OAuthMemberService service =
            new OAuthMemberService(socialRepository, memberRepository, refreshTokenRepository);

    private Member existing(MemberRole role, MemberStatus status, boolean verified) {
        Member member = Member.builder().email(EMAIL).password("hash").name("기존").role(role).status(status).build();
        ReflectionTestUtils.setField(member, "id", 7L);
        if (verified) {
            member.verifyEmail(OffsetDateTime.now());
        }
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.of(member));
        when(memberRepository.findById(7L)).thenReturn(Optional.of(member));
        return member;
    }

    private void assertCode(OAuthProfile profile, AuthErrorCode code) {
        assertThatThrownBy(() -> service.resolve(profile)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("처음 보는 이메일 → 비밀번호 없는 CUSTOMER 로 가입 + 연결")
    void createsCustomer() {
        when(memberRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> inv.getArgument(0));

        Member member = service.resolve(VERIFIED);

        assertThat(member.getRole()).isEqualTo(MemberRole.CUSTOMER);
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getPassword()).isNull();
        assertThat(member.getEmailVerifiedAt()).isNotNull();
        assertThat(member.getName()).isEqualTo("홍길동");
        verify(socialRepository).save(any(MemberSocial.class));
    }

    @Test
    @DisplayName("이메일을 인증한 기존 회원 → 비밀번호를 그대로 두고 연결")
    void linksVerifiedMember() {
        Member member = existing(MemberRole.CUSTOMER, MemberStatus.ACTIVE, true);

        assertThat(service.resolve(VERIFIED)).isSameAs(member);
        assertThat(member.getPassword()).isEqualTo("hash");
        verify(refreshTokenRepository, never()).revokeAllByMemberId(any());
        verify(socialRepository).save(any(MemberSocial.class));
    }

    @Test
    @DisplayName("이메일을 인증한 적 없는 기존 계정 → 비밀번호·세션을 지우고 연결 (선점 계정 탈취 방지)")
    void takesOverUnverifiedMember() {
        Member member = existing(MemberRole.CUSTOMER, MemberStatus.PENDING, false);

        service.resolve(VERIFIED);

        assertThat(member.getPassword()).isNull();
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        verify(refreshTokenRepository).revokeAllByMemberId(7L);
    }

    @Test
    @DisplayName("제공자가 확인해 주지 않은 이메일·이메일 없음 → 거부, 아무것도 저장하지 않는다")
    void rejectsUnverifiedEmail() {
        existing(MemberRole.CUSTOMER, MemberStatus.ACTIVE, true);

        assertCode(new OAuthProfile(OAuthProvider.KAKAO, "k-1", EMAIL, false, null), AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        assertCode(new OAuthProfile(OAuthProvider.KAKAO, "k-1", null, true, null), AuthErrorCode.OAUTH_EMAIL_REQUIRED);
        verify(socialRepository, never()).save(any());
    }

    @Test
    @DisplayName("직원 계정 이메일 → 거부")
    void rejectsStaff() {
        existing(MemberRole.AGENT, MemberStatus.ACTIVE, true);

        assertCode(VERIFIED, AuthErrorCode.OAUTH_STAFF_NOT_ALLOWED);
        verify(socialRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 연결된 소셜 계정 → 이메일이 바뀌었어도 같은 회원, 비활성 회원은 거부")
    void usesExistingLink() {
        Member member = existing(MemberRole.CUSTOMER, MemberStatus.ACTIVE, true);
        when(socialRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "g-1"))
                .thenReturn(Optional.of(new MemberSocial(7L, OAuthProvider.GOOGLE, "g-1")));
        OAuthProfile changedEmail = new OAuthProfile(OAuthProvider.GOOGLE, "g-1", "other@helpnest.local", false, null);

        assertThat(service.resolve(changedEmail)).isSameAs(member);
        verify(socialRepository, never()).save(any());

        member.changeStatus(MemberStatus.INACTIVE);
        assertCode(changedEmail, AuthErrorCode.INACTIVE_MEMBER);
    }

    @Test
    @DisplayName("제공자 응답 → 프로필: 카카오는 유효+인증 둘 다 참이어야 확인된 이메일")
    void mapsProviderResponses() {
        assertThat(OAuthProvider.GOOGLE.profile(Map.of("sub", "g-1", "email", EMAIL, "email_verified", true,
                "name", "홍길동"))).isEqualTo(VERIFIED);

        Map<String, Object> kakao = Map.of("id", 123L, "kakao_account", Map.of("email", EMAIL,
                "is_email_valid", true, "is_email_verified", false, "profile", Map.of("nickname", "길동")));
        assertThat(OAuthProvider.KAKAO.profile(kakao))
                .isEqualTo(new OAuthProfile(OAuthProvider.KAKAO, "123", EMAIL, false, "길동"));
        // 이메일 동의를 하지 않으면 kakao_account 에 이메일이 없다
        assertThat(OAuthProvider.KAKAO.profile(Map.of("id", 123L)).email()).isNull();
    }
}
