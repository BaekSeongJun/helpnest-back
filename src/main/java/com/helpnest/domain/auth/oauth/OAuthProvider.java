// @owner BSJ
package com.helpnest.domain.auth.oauth;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 소셜 로그인 제공자와 각자의 엔드포인트·응답 형식 (FR-AUTH-11) */
public enum OAuthProvider {

    GOOGLE("https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo", "openid email profile") {
        @Override
        OAuthProfile profile(Map<String, Object> body) {
            return new OAuthProfile(this, text(body.get("sub")), text(body.get("email")),
                    Boolean.TRUE.equals(body.get("email_verified")), text(body.get("name")));
        }
    },
    KAKAO("https://kauth.kakao.com/oauth/authorize", "https://kauth.kakao.com/oauth/token",
            "https://kapi.kakao.com/v2/user/me", "account_email profile_nickname") {
        @Override
        OAuthProfile profile(Map<String, Object> body) {
            Map<?, ?> account = body.get("kakao_account") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> kakaoProfile = account.get("profile") instanceof Map<?, ?> m ? m : Map.of();
            // 카카오는 "유효"와 "인증됨"을 따로 준다 — 둘 다 참일 때만 본인 이메일로 믿는다
            boolean verified = Boolean.TRUE.equals(account.get("is_email_valid"))
                    && Boolean.TRUE.equals(account.get("is_email_verified"));
            return new OAuthProfile(this, text(body.get("id")), text(account.get("email")), verified,
                    text(kakaoProfile.get("nickname")));
        }
    };

    final String authorizeUri;
    final String tokenUri;
    final String userInfoUri;
    final String scope;

    OAuthProvider(String authorizeUri, String tokenUri, String userInfoUri, String scope) {
        this.authorizeUri = authorizeUri;
        this.tokenUri = tokenUri;
        this.userInfoUri = userInfoUri;
        this.scope = scope;
    }

    /** 사용자 정보 응답 → 공통 프로필 */
    abstract OAuthProfile profile(Map<String, Object> body);

    /** URL 경로의 소문자 이름(google, kakao) → 제공자 */
    public static Optional<OAuthProvider> fromPath(String name) {
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
