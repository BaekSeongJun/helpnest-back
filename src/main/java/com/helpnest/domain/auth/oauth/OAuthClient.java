// @owner BSJ
package com.helpnest.domain.auth.oauth;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 제공자와의 통신 — 인가 주소 만들기, 인가 코드 → 액세스 토큰 → 사용자 정보.
 * redirect_uri 는 프론트 도메인의 /api 경로다: 브라우저는 Next.js 프록시를 거쳐 들어오므로(docs/02 §2.1)
 * 콜백에서 심는 Refresh 쿠키가 프론트 도메인 쿠키가 된다.
 */
@Component
public class OAuthClient {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };

    private final OAuthProperties properties;
    private final String frontOrigin;
    private final RestClient restClient;

    public OAuthClient(OAuthProperties properties, @Value("${app.front-origin}") String frontOrigin) {
        this.properties = properties;
        this.frontOrigin = frontOrigin;
        // 제공자가 느려도 콜백 요청 스레드가 오래 묶이지 않게
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled(OAuthProvider provider) {
        return properties.of(provider) != null && properties.of(provider).enabled();
    }

    public String authorizeUrl(OAuthProvider provider, String state) {
        // 값마다 엄격하게 인코딩한다 — redirect_uri 의 ':' '/' 도 그대로 두면 제공자에 따라 거절된다
        return provider.authorizeUri
                + "?response_type=code"
                + "&client_id=" + encode(properties.of(provider).clientId())
                + "&redirect_uri=" + encode(redirectUri(provider))
                + "&scope=" + encode(provider.scope)
                + "&state=" + encode(state);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** @throws RuntimeException 제공자가 거절했거나(만료·재사용 코드) 응답하지 않을 때 — 호출자가 로그인 실패로 처리 */
    public OAuthProfile fetchProfile(OAuthProvider provider, String code) {
        OAuthProperties.Client client = properties.of(provider);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri(provider));
        form.add("client_id", client.clientId());
        form.add("client_secret", client.clientSecret());
        Map<String, Object> token = restClient.post().uri(provider.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(JSON);
        if (token == null || !(token.get("access_token") instanceof String accessToken)) {
            throw new IllegalStateException("access_token 없음");
        }
        Map<String, Object> user = restClient.get().uri(provider.userInfoUri)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve().body(JSON);
        if (user == null) {
            throw new IllegalStateException("사용자 정보 없음");
        }
        return provider.profile(user);
    }

    private String redirectUri(OAuthProvider provider) {
        return frontOrigin + "/api/auth/oauth2/callback/" + provider.path();
    }
}
