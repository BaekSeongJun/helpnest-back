// @owner BSJ
package com.helpnest.global.security;

import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Access 토큰 발급. 검증은 SecurityConfig 의 JwtDecoder(Resource Server)가 한다.
 * <p>
 * 클레임: sub = memberId, role = CUSTOMER/AGENT/LEAD/ADMIN.
 * 컨트롤러에서 로그인 회원 id: {@code @AuthenticationPrincipal Jwt jwt} → {@link #memberId(Jwt)}
 */
@Component
public class JwtProvider {

    public static final String ROLE_CLAIM = "role";
    /** 비회원 조회용 Guest 토큰: role = GUEST, ticketId 클레임 (docs/02, S1-5 에서 발급) */
    public static final String GUEST_ROLE = "GUEST";
    public static final String TICKET_ID_CLAIM = "ticketId";

    private final JwtEncoder jwtEncoder;
    private final Duration accessTtl;

    public JwtProvider(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.accessTtl = properties.accessTtl();
    }

    public String createAccessToken(Long memberId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(memberId))
                .claim(ROLE_CLAIM, role)
                .issuedAt(now)
                .expiresAt(now.plus(accessTtl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Guest 토큰(sub = guest:{ticketId})은 회원이 아니므로 회원 전용 API 에서 403 */
    public static Long memberId(Jwt jwt) {
        if (GUEST_ROLE.equals(jwt.getClaimAsString(ROLE_CLAIM))) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return Long.valueOf(jwt.getSubject());
    }

    /** HS256 은 256bit 이상 키가 필요 — 짧으면 기동 시점에 실패시킨다. */
    public static SecretKey secretKey(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET 은 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
