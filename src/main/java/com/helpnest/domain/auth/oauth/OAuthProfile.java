// @owner BSJ
package com.helpnest.domain.auth.oauth;

/**
 * 제공자가 알려 준 사용자.
 *
 * @param providerUserId 제공자 쪽 고유 id — 로그인 키
 * @param email          동의하지 않았으면 null
 * @param emailVerified  제공자가 그 이메일을 본인 것으로 확인했는지
 * @param name           없으면 null
 */
public record OAuthProfile(OAuthProvider provider, String providerUserId, String email, boolean emailVerified,
        String name) {
}
