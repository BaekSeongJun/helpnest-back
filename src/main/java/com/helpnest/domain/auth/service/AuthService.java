// @owner BSJ
package com.helpnest.domain.auth.service;

import com.helpnest.domain.auth.dto.AuthResponse;
import com.helpnest.domain.auth.dto.LoginRequest;
import com.helpnest.domain.auth.dto.SignupRequest;
import com.helpnest.domain.auth.entity.RefreshToken;
import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProperties;
import com.helpnest.global.security.JwtProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 회원가입·로그인·Refresh 회전·로그아웃 (FR-AUTH-01, 02).
 * Refresh 원문은 쿠키로만 나가고 DB 에는 SHA-256 해시만 저장한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;

    /** 컨트롤러가 body 와 Set-Cookie 를 나눠 쓰도록 함께 반환 */
    public record Tokens(AuthResponse body, String refreshToken) {
    }

    @Transactional
    public MemberResponse signup(SignupRequest req) {
        if (memberRepository.existsByEmail(req.email())) {
            throw new BusinessException(MemberErrorCode.EMAIL_DUPLICATED);
        }
        Member member = Member.builder()
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(StringUtils.hasText(req.phone()) ? req.phone() : null)
                .role(MemberRole.CUSTOMER)
                .build();
        return MemberResponse.from(memberRepository.save(member));
    }

    @Transactional
    public Tokens login(LoginRequest req) {
        Member member = memberRepository.findByEmail(req.email())
                .filter(m -> passwordEncoder.matches(req.password(), m.getPassword()))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_CREDENTIALS));
        if (member.getStatus() == MemberStatus.INACTIVE) {
            throw new BusinessException(AuthErrorCode.INACTIVE_MEMBER);
        }
        return issue(member);
    }

    /**
     * Refresh 회전: 쓰인 토큰은 폐기하고 새로 발급.
     * 이미 폐기된 토큰이 다시 오면 탈취로 보고 그 회원의 Refresh 를 전부 폐기한다 — 예외를 던져도 폐기는 커밋되도록 noRollbackFor.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Tokens refresh(String rawToken) {
        RefreshToken token = findToken(rawToken);
        if (token.isRevoked()) {
            refreshTokenRepository.revokeAllByMemberId(token.getMemberId());
            throw new BusinessException(AuthErrorCode.REFRESH_INVALID);
        }
        if (!token.isUsable(OffsetDateTime.now())) {
            throw new BusinessException(AuthErrorCode.REFRESH_INVALID);
        }
        token.revoke();

        Member member = memberRepository.findById(token.getMemberId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_INVALID));
        if (member.getStatus() == MemberStatus.INACTIVE) {
            refreshTokenRepository.revokeAllByMemberId(member.getId());
            throw new BusinessException(AuthErrorCode.INACTIVE_MEMBER);
        }
        return issue(member);
    }

    /** 쿠키가 없거나 이미 무효여도 로그아웃은 성공으로 처리 */
    @Transactional
    public void logout(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHash(sha256(rawToken)).ifPresent(RefreshToken::revoke);
    }

    private RefreshToken findToken(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BusinessException(AuthErrorCode.REFRESH_INVALID);
        }
        return refreshTokenRepository.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.REFRESH_INVALID));
    }

    private Tokens issue(Member member) {
        String accessToken = jwtProvider.createAccessToken(member.getId(), member.getRole().name());

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(new RefreshToken(member.getId(), sha256(rawRefresh),
                OffsetDateTime.now().plus(jwtProperties.refreshTtl())));

        return new Tokens(new AuthResponse(accessToken, MemberResponse.from(member)), rawRefresh);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // 모든 JVM 에 SHA-256 은 필수 포함
        }
    }
}
