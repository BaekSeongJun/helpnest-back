// @owner BSJ
package com.helpnest.domain.auth.service;

import com.helpnest.domain.auth.entity.PasswordResetToken;
import com.helpnest.domain.auth.entity.PasswordResetToken.TargetType;
import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.repository.PasswordResetTokenRepository;
import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.port.TicketGuestPort;
import com.helpnest.global.error.BusinessException;
import com.helpnest.infra.mail.PasswordResetMailCommand;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 비밀번호 찾기·재설정 (FR-AUTH-07). 링크 토큰은 30분·1회용, 원문은 메일에만.
 * 비회원 조회 비밀번호 재설정(FR-AUTH-09)도 같은 토큰 테이블을 target_type 으로 나눠 쓴다.
 *
 * <p>요청 API 는 계정이 있든 없든 같은 응답을 준다. 메일은 {@link PasswordResetMailListener} 가 커밋 후 비동기로
 * 보내므로 메일 전송 시간이 응답 시간에 섞이지 않는다 — 섞이면 "느리면 가입된 이메일"이 드러난다.
 */
@Service
@Transactional(readOnly = true)
public class PasswordResetService {

    static final Duration TTL = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TicketGuestPort ticketGuestPort;
    private final ApplicationEventPublisher events;
    private final String frontOrigin;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository, MemberRepository memberRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
            TicketGuestPort ticketGuestPort, ApplicationEventPublisher events,
            @Value("${app.front-origin}") String frontOrigin) {
        this.tokenRepository = tokenRepository;
        this.memberRepository = memberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.ticketGuestPort = ticketGuestPort;
        this.events = events;
        this.frontOrigin = frontOrigin;
    }

    /** 활성 회원일 때만 메일. 없는 이메일·비활성 회원도 호출자는 같은 200 을 준다 */
    @Transactional
    public void requestMemberReset(String email) {
        memberRepository.findByEmail(email.trim())
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .ifPresent(m -> {
                    String raw = issue(TargetType.MEMBER, m.getId());
                    events.publishEvent(new PasswordResetMailCommand(m.getEmail(),
                            frontOrigin + "/reset-password?token=" + raw, false));
                });
    }

    /** 새 비밀번호 저장 + Refresh 전부 폐기 (다른 기기 로그인도 끊는다, FR-AUTH-08 과 같은 정책) */
    @Transactional
    public void resetMemberPassword(String rawToken, String newPassword) {
        Long memberId = consume(rawToken, TargetType.MEMBER);
        Member member = memberRepository.findById(memberId)
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.RESET_TOKEN_INVALID));
        member.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllByMemberId(memberId);
    }

    /**
     * 비회원 조회 비밀번호 재설정 메일 (FR-AUTH-09). 티켓번호+이메일이 비회원 티켓과 맞을 때만 메일,
     * 호출자는 결과와 무관하게 같은 200 을 준다. 메일은 입력한 주소로 보낸다 — verifyGuest 가 대소문자를
     * 무시하고 일치를 확인했으므로 접수 때의 주소와 같은 사람이다.
     */
    @Transactional
    public void requestGuestReset(String ticketNo, String email) {
        String to = email.trim();
        Long ticketId = ticketGuestPort.verifyGuest(ticketNo.trim(), to);
        if (ticketId == null) {
            return;
        }
        String raw = issue(TargetType.GUEST_TICKET, ticketId);
        events.publishEvent(new PasswordResetMailCommand(to,
                frontOrigin + "/inquiry/lookup/reset?token=" + raw, true));
    }

    /** 해싱은 여기서 — 원문 비밀번호는 박민재 포트로 넘기지 않는다 (docs/02 §5.2 TicketGuestPort) */
    @Transactional
    public void resetGuestPassword(String rawToken, String newPassword) {
        Long ticketId = consume(rawToken, TargetType.GUEST_TICKET);
        ticketGuestPort.updateGuestPassword(ticketId, passwordEncoder.encode(newPassword));
    }

    /** @return 메일 링크에 넣을 원문 토큰 */
    String issue(TargetType type, Long targetId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokenRepository.save(new PasswordResetToken(type, targetId, AuthService.sha256(raw),
                OffsetDateTime.now().plus(TTL)));
        return raw;
    }

    /**
     * 토큰을 쓰고 대상 id 를 돌려준다. 없음·만료·사용됨·유형 불일치는 모두 같은 오류.
     * 같은 대상의 다른 링크도 함께 사용 처리해 재설정 후 옛 메일로 다시 바꿀 수 없게 한다.
     */
    Long consume(String rawToken, TargetType type) {
        if (!StringUtils.hasText(rawToken)) {
            throw new BusinessException(AuthErrorCode.RESET_TOKEN_INVALID);
        }
        OffsetDateTime now = OffsetDateTime.now();
        PasswordResetToken token = tokenRepository.findByTokenHash(AuthService.sha256(rawToken))
                .filter(t -> t.isUsable(type, now))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.RESET_TOKEN_INVALID));
        tokenRepository.useAllByTarget(type, token.getTargetId(), now);
        return token.getTargetId();
    }
}
