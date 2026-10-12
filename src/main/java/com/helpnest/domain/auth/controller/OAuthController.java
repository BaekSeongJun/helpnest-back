// @owner BSJ
package com.helpnest.domain.auth.controller;

import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.oauth.OAuthClient;
import com.helpnest.domain.auth.oauth.OAuthMemberService;
import com.helpnest.domain.auth.oauth.OAuthProvider;
import com.helpnest.domain.auth.service.AuthService;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 소셜 로그인 (FR-AUTH-11) — OAuth2 인가 코드 흐름. 브라우저가 직접 이동하는 주소라 응답은 항상 302.
 * <pre>
 * /authorize/{provider} → 제공자 로그인 → /callback/{provider}?code&amp;state → Refresh 쿠키 + 프론트 홈
 * </pre>
 * 성공하면 일반 로그인과 같은 Refresh 쿠키만 심는다. Access 토큰은 프론트가 뜨면서 /api/auth/refresh 로 받는다.
 * 실패는 모두 {@code /login?error=코드} 로 보낸다.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/oauth2")
public class OAuthController {

    /** 로그인 CSRF 방지: 인가를 시작한 브라우저만 콜백을 끝낼 수 있게 state 를 쿠키와 대조한다 */
    static final String STATE_COOKIE = "oauthState";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthClient oauthClient;
    private final OAuthMemberService oauthMemberService;
    private final AuthService authService;
    private final JwtProperties jwtProperties;
    private final String frontOrigin;

    public OAuthController(OAuthClient oauthClient, OAuthMemberService oauthMemberService, AuthService authService,
            JwtProperties jwtProperties, @Value("${app.front-origin}") String frontOrigin) {
        this.oauthClient = oauthClient;
        this.oauthMemberService = oauthMemberService;
        this.authService = authService;
        this.jwtProperties = jwtProperties;
        this.frontOrigin = frontOrigin;
    }

    @GetMapping("/authorize/{provider}")
    public ResponseEntity<Void> authorize(@PathVariable String provider) {
        OAuthProvider p = OAuthProvider.fromPath(provider).filter(oauthClient::enabled).orElse(null);
        if (p == null) {
            return fail(AuthErrorCode.OAUTH_FAILED);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauthClient.authorizeUrl(p, state)))
                .header(HttpHeaders.SET_COOKIE, stateCookie(state, STATE_TTL).toString())
                .build();
    }

    @GetMapping("/callback/{provider}")
    public ResponseEntity<Void> callback(@PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @CookieValue(name = STATE_COOKIE, required = false) String expectedState) {
        OAuthProvider p = OAuthProvider.fromPath(provider).filter(oauthClient::enabled).orElse(null);
        // code 가 없으면 사용자가 동의 화면에서 취소한 것
        if (p == null || code == null || !sameState(state, expectedState)) {
            return fail(AuthErrorCode.OAUTH_FAILED);
        }
        try {
            Member member = oauthMemberService.resolve(oauthClient.fetchProfile(p, code));
            AuthService.Tokens tokens = authService.issueTokens(member);
            return redirect(frontOrigin + "/")
                    .header(HttpHeaders.SET_COOKIE, AuthController
                            .refreshCookie(jwtProperties, tokens.refreshToken(), jwtProperties.refreshTtl()).toString())
                    .build();
        } catch (BusinessException e) {
            return fail(e.getErrorCode() instanceof AuthErrorCode c ? c : AuthErrorCode.OAUTH_FAILED);
        } catch (RuntimeException e) {
            // 제공자 오류(만료·재사용 코드, 타임아웃). 코드·토큰은 로그에 남기지 않는다
            log.warn("[oauth] {} 로그인 실패: {}", p, e.getClass().getSimpleName());
            return fail(AuthErrorCode.OAUTH_FAILED);
        }
    }

    private static boolean sameState(String state, String expected) {
        return state != null && expected != null && MessageDigest.isEqual(
                state.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Void> fail(AuthErrorCode code) {
        return redirect(frontOrigin + "/login?error=" + code.code()).build();
    }

    /** 성공·실패 모두 state 쿠키를 지운다 — 한 번 쓴 state 로 콜백을 다시 부를 수 없게 */
    private ResponseEntity.BodyBuilder redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location))
                .header(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie stateCookie(String value, Duration maxAge) {
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(jwtProperties.refreshCookieSecure())
                .sameSite("Lax")   // 제공자에서 돌아오는 최상위 GET 이동에 실려야 한다
                .path("/api/auth/oauth2")
                .maxAge(maxAge)
                .build();
    }
}
