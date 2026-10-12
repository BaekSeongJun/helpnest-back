// @owner BSJ
package com.helpnest.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.auth.dto.LoginRequest;
import com.helpnest.domain.auth.dto.SignupRequest;
import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.event.MemberSignedUpEvent;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.RateLimiter;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 이메일 인증이 켜진 가입: PENDING 저장·이벤트 발행·로그인 거부·인증 전 재가입 (back #117 접점).
 * 플래그를 바꾼 Spring 컨텍스트를 새로 띄우지 않으려고 단위 테스트로 둔다 — 컨텍스트마다 DB 연결 풀이 하나씩 늘어난다.
 */
class EmailVerificationSignupTest {

    private static final String EMAIL = "verify-test@helpnest.local";

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    /** 저장된 회원 1명을 흉내 낸다 */
    private final AtomicReference<Member> saved = new AtomicReference<>();

    private AuthService service(boolean emailVerification) {
        when(memberRepository.findByEmail(EMAIL)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        when(memberRepository.save(any(Member.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        return new AuthService(memberRepository, null, new BCryptPasswordEncoder(4), null, null, null,
                mock(RateLimiter.class), events, emailVerification);
    }

    private static SignupRequest signup(String password) {
        return new SignupRequest(EMAIL, password, "테스트", null);
    }

    private static void assertCode(Runnable call, Object code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("가입 → PENDING + 이벤트, 인증 전 로그인은 비밀번호가 맞을 때만 미인증 오류")
    void pendingUntilVerified() {
        AuthService service = service(true);
        service.signup(signup("Test1234!"));

        assertThat(saved.get().getStatus()).isEqualTo(MemberStatus.PENDING);
        verify(events).publishEvent(new MemberSignedUpEvent(null, EMAIL, "테스트"));
        assertCode(() -> service.login(new LoginRequest(EMAIL, "Test1234!")), AuthErrorCode.EMAIL_NOT_VERIFIED);
        // 비밀번호가 틀리면 미인증 여부를 알려 주지 않는다
        assertCode(() -> service.login(new LoginRequest(EMAIL, "Wrong1234!")), AuthErrorCode.INVALID_CREDENTIALS);

        saved.get().verifyEmail(OffsetDateTime.now());
        assertThat(saved.get().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(saved.get().getEmailVerifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("인증 전 재가입은 덮어쓰고 이벤트를 다시 낸다, 인증 후에는 중복 오류")
    void reRegisterWhilePending() {
        AuthService service = service(true);
        service.signup(signup("Squatter1234!"));
        service.signup(signup("Owner1234!"));

        verify(events, times(2)).publishEvent(any(MemberSignedUpEvent.class));
        verify(memberRepository, times(1)).save(any(Member.class));
        assertCode(() -> service.login(new LoginRequest(EMAIL, "Squatter1234!")), AuthErrorCode.INVALID_CREDENTIALS);
        assertCode(() -> service.login(new LoginRequest(EMAIL, "Owner1234!")), AuthErrorCode.EMAIL_NOT_VERIFIED);

        saved.get().verifyEmail(OffsetDateTime.now());
        assertCode(() -> service.signup(signup("Other1234!")), MemberErrorCode.EMAIL_DUPLICATED);
    }

    @Test
    @DisplayName("인증을 끄면 지금처럼 바로 ACTIVE, 이벤트 없음")
    void activeWhenDisabled() {
        service(false).signup(signup("Test1234!"));

        assertThat(saved.get().getStatus()).isEqualTo(MemberStatus.ACTIVE);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("관리자가 비활성화한 계정은 인증 링크로 되살아나지 않는다")
    void verifyDoesNotReviveInactive() {
        service(true).signup(signup("Test1234!"));
        saved.get().changeStatus(MemberStatus.INACTIVE);

        saved.get().verifyEmail(OffsetDateTime.now());
        assertThat(saved.get().getStatus()).isEqualTo(MemberStatus.INACTIVE);
    }
}
