// @owner BSJ
package com.helpnest.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helpnest.global.error.BusinessException;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtProviderTest {

    private static final SecretKey KEY = JwtProvider.secretKey("test-secret-key-for-unit-test-32bytes!!");
    private static final SecretKey OTHER_KEY = JwtProvider.secretKey("another-secret-key-for-unit-test-32bytes");

    private static JwtProvider provider(Duration ttl) {
        return new JwtProvider(NimbusJwtEncoder.withSecretKey(KEY).build(), new JwtProperties(null, ttl, Duration.ofDays(14), true));
    }

    private static JwtDecoder decoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Test
    @DisplayName("발급한 토큰을 검증하면 sub=memberId, role 클레임이 담겨 있다")
    void issueAndDecode() {
        String token = provider(Duration.ofMinutes(30)).createAccessToken(7L, "AGENT");

        Jwt jwt = decoder(KEY).decode(token);

        assertThat(JwtProvider.memberId(jwt)).isEqualTo(7L);
        assertThat(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM)).isEqualTo("AGENT");
    }

    @Test
    @DisplayName("만료된 토큰은 거부한다")
    void expired() {
        // 기본 검증기의 시계 오차 허용(60초)보다 충분히 과거에 만료된 토큰
        Instant past = Instant.now().minus(Duration.ofHours(1));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1").issuedAt(past).expiresAt(past.plus(Duration.ofMinutes(30))).build();
        String token = NimbusJwtEncoder.withSecretKey(KEY).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> decoder(KEY).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("다른 키로 서명된(위조) 토큰은 거부한다")
    void forged() {
        String token = provider(Duration.ofMinutes(30)).createAccessToken(1L, "ADMIN");

        assertThatThrownBy(() -> decoder(OTHER_KEY).decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("32바이트 미만 시크릿이면 기동 시점에 실패한다")
    void shortSecret() {
        assertThatThrownBy(() -> JwtProvider.secretKey("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtProvider.secretKey(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("memberId: Guest 토큰(sub=guest:N)은 숫자 변환 500 대신 403")
    void memberIdRejectsGuest() {
        Jwt guest = Jwt.withTokenValue("t").header("alg", "HS256").subject("guest:5")
                .claim(JwtProvider.ROLE_CLAIM, JwtProvider.GUEST_ROLE).build();

        assertThatThrownBy(() -> JwtProvider.memberId(guest)).isInstanceOf(BusinessException.class);
    }
}
