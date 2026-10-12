// @owner BSJ
package com.helpnest.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.auth.error.AuthErrorCode;
import com.helpnest.domain.auth.oauth.OAuthClient;
import com.helpnest.domain.auth.oauth.OAuthMemberService;
import com.helpnest.domain.auth.oauth.OAuthProfile;
import com.helpnest.domain.auth.oauth.OAuthProvider;
import com.helpnest.domain.auth.service.AuthService;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.security.JwtProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** 소셜 로그인 이동·콜백: state 대조, 성공 시 Refresh 쿠키, 실패 시 로그인 화면으로 */
class OAuthControllerTest {

    private static final String FRONT = "https://front.test";

    private final OAuthClient oauthClient = mock(OAuthClient.class);
    private final OAuthMemberService memberService = mock(OAuthMemberService.class);
    private final AuthService authService = mock(AuthService.class);
    private final OAuthController controller = new OAuthController(oauthClient, memberService, authService,
            new JwtProperties("s", Duration.ofMinutes(30), Duration.ofDays(14), true), FRONT);

    private static String location(ResponseEntity<Void> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        return String.valueOf(response.getHeaders().getLocation());
    }

    private static List<String> cookies(ResponseEntity<Void> response) {
        return response.getHeaders().get(HttpHeaders.SET_COOKIE);
    }

    @Test
    @DisplayName("authorize → 제공자로 이동 + httpOnly state 쿠키, 꺼진·모르는 제공자는 로그인 화면으로")
    void authorize() {
        when(oauthClient.enabled(OAuthProvider.GOOGLE)).thenReturn(true);
        when(oauthClient.authorizeUrl(any(), any())).thenAnswer(inv -> "https://idp.test/auth?state=" + inv.getArgument(1));

        ResponseEntity<Void> response = controller.authorize("google");
        String state = location(response).substring("https://idp.test/auth?state=".length());
        assertThat(state).hasSizeGreaterThanOrEqualTo(43);   // 32바이트 무작위
        assertThat(cookies(response)).singleElement().asString()
                .contains("oauthState=" + state, "HttpOnly", "Secure", "SameSite=Lax", "Path=/api/auth/oauth2");

        assertThat(location(controller.authorize("kakao"))).isEqualTo(FRONT + "/login?error=AUTH_OAUTH_FAILED");
        assertThat(location(controller.authorize("naver"))).isEqualTo(FRONT + "/login?error=AUTH_OAUTH_FAILED");
    }

    @Test
    @DisplayName("callback: state 가 쿠키와 다르거나 없으면 제공자를 호출하지 않고 실패")
    void rejectsStateMismatch() {
        when(oauthClient.enabled(OAuthProvider.GOOGLE)).thenReturn(true);

        for (String[] c : new String[][] {{"s1", "s2"}, {"s1", null}, {null, "s1"}}) {
            assertThat(location(controller.callback("google", "code", c[0], c[1])))
                    .isEqualTo(FRONT + "/login?error=AUTH_OAUTH_FAILED");
        }
        assertThat(location(controller.callback("google", null, "s1", "s1")))   // 동의 화면에서 취소
                .isEqualTo(FRONT + "/login?error=AUTH_OAUTH_FAILED");
        verify(oauthClient, never()).fetchProfile(any(), any());
    }

    @Test
    @DisplayName("callback 성공 → 프론트 홈 + Refresh 쿠키, state 쿠키 삭제")
    void success() {
        OAuthProfile profile = new OAuthProfile(OAuthProvider.GOOGLE, "g-1", "a@b.c", true, "이름");
        Member member = Member.builder().email("a@b.c").name("이름").role(MemberRole.CUSTOMER).build();
        when(oauthClient.enabled(OAuthProvider.GOOGLE)).thenReturn(true);
        when(oauthClient.fetchProfile(OAuthProvider.GOOGLE, "code")).thenReturn(profile);
        when(memberService.resolve(profile)).thenReturn(member);
        when(authService.issueTokens(member)).thenReturn(new AuthService.Tokens(null, "raw-refresh"));

        ResponseEntity<Void> response = controller.callback("google", "code", "s1", "s1");

        assertThat(location(response)).isEqualTo(FRONT + "/");
        assertThat(cookies(response)).anySatisfy(c -> assertThat(c).contains("refreshToken=raw-refresh", "HttpOnly"))
                .anySatisfy(c -> assertThat(c).contains("oauthState=;", "Max-Age=0"));
    }

    @Test
    @DisplayName("callback: 연결 규칙 위반은 그 오류 코드로, 제공자 오류는 일반 실패로")
    void failures() {
        when(oauthClient.enabled(OAuthProvider.GOOGLE)).thenReturn(true);
        when(oauthClient.fetchProfile(any(), any())).thenReturn(mock(OAuthProfile.class));
        when(memberService.resolve(any())).thenThrow(new BusinessException(AuthErrorCode.OAUTH_EMAIL_REQUIRED));
        assertThat(location(controller.callback("google", "code", "s1", "s1")))
                .isEqualTo(FRONT + "/login?error=AUTH_OAUTH_EMAIL_REQUIRED");

        when(oauthClient.fetchProfile(any(), any())).thenThrow(new IllegalStateException("401 from idp"));
        ResponseEntity<Void> response = controller.callback("google", "code", "s1", "s1");
        assertThat(location(response)).isEqualTo(FRONT + "/login?error=AUTH_OAUTH_FAILED");
        assertThat(cookies(response)).noneMatch(c -> c.startsWith("refreshToken"));
    }
}
