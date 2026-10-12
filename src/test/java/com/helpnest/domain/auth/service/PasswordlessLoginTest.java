// @owner BSJ
package com.helpnest.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.helpnest.domain.auth.dto.LoginRequest;
import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.RateLimiter;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 비밀번호 없는 소셜 회원은 비밀번호 로그인이 되지 않는다 — 타이밍용 더미 해시의 원문으로도 */
class PasswordlessLoginTest {

    @Test
    @DisplayName("소셜로만 가입한 회원 + 더미 해시 원문 → 로그인 실패")
    void dummyPasswordDoesNotLogIn() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        Member social = Member.builder().email("s@helpnest.local").name("소셜").role(MemberRole.CUSTOMER).build();
        when(memberRepository.findByEmail("s@helpnest.local")).thenReturn(Optional.of(social));
        AuthService service = new AuthService(memberRepository, null, new BCryptPasswordEncoder(), null, null, null,
                mock(RateLimiter.class), mock(ApplicationEventPublisher.class), false);

        assertThatThrownBy(() -> service.login(new LoginRequest("s@helpnest.local", "helpnest-dummy")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }
}
