// @owner BSJ
package com.helpnest.domain.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** application.yml 의 app.oauth2.*. id·secret 이 비어 있는 제공자는 꺼진 것으로 본다 */
@ConfigurationProperties("app.oauth2")
public record OAuthProperties(Client google, Client kakao) {

    public record Client(String clientId, String clientSecret) {
        boolean enabled() {
            return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
        }
    }

    Client of(OAuthProvider provider) {
        return provider == OAuthProvider.GOOGLE ? google : kakao;
    }
}
