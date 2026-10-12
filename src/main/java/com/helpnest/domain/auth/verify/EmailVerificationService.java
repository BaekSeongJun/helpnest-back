// @owner SSJ
package com.helpnest.domain.auth.verify;

import com.helpnest.domain.auth.entity.PasswordResetToken.TargetType;
import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.PasswordResetTokenRepository;
import com.helpnest.domain.auth.service.PasswordResetService;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이메일 인증 링크 발급·확인 (back #117). 토큰은 비밀번호 재설정과 같은 테이블(EMAIL_VERIFY, 30분·1회용).
 * 메일은 이 트랜잭션이 커밋된 뒤 {@link EmailVerificationMailer} 가 보낸다 — 커밋 전에 보내면 롤백 시 죽은 링크가 나간다.
 */
@Service
public class EmailVerificationService {

    private final PasswordResetService passwordResetService;
    private final PasswordResetTokenRepository tokenRepository;
    private final MemberRepository memberRepository;
    private final String frontOrigin;

    public EmailVerificationService(PasswordResetService passwordResetService,
            PasswordResetTokenRepository tokenRepository, MemberRepository memberRepository,
            @Value("${app.front-origin}") String frontOrigin) {
        this.passwordResetService = passwordResetService;
        this.tokenRepository = tokenRepository;
        this.memberRepository = memberRepository;
        this.frontOrigin = frontOrigin;
    }

    /** 새 링크. 이전에 보낸 링크는 사용 처리한다 — issue() 는 옛 토큰을 지우지 않으므로 재발송·재가입 시 여기서 막는다 */
    @Transactional
    public String issueLink(Long memberId) {
        tokenRepository.useAllByTarget(TargetType.EMAIL_VERIFY, memberId, OffsetDateTime.now());
        return frontOrigin + "/verify-email?token=" + passwordResetService.issue(TargetType.EMAIL_VERIFY, memberId);
    }

    /** 없음·만료·사용됨은 모두 AUTH_RESET_TOKEN_INVALID. PENDING 만 ACTIVE 로 오른다(Member.verifyEmail) */
    @Transactional
    public void verify(String rawToken) {
        Long memberId = passwordResetService.consume(rawToken, TargetType.EMAIL_VERIFY);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.RESET_TOKEN_INVALID));
        member.verifyEmail(OffsetDateTime.now());
    }
}
