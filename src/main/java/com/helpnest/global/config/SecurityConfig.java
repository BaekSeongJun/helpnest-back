// @owner BSJ
package com.helpnest.global.config;

import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.error.CommonErrorCode;
import com.helpnest.global.error.ErrorCode;
import com.helpnest.global.security.JwtProperties;
import com.helpnest.global.security.JwtProvider;
import com.helpnest.global.security.RateLimitFilter;
import com.helpnest.global.security.RateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증·인가 공용 설정 (docs/02 §4, 04 권한 열).
 * <ul>
 *   <li>URL 규칙은 prefix 단위의 최소 역할만 건다. "ADMIN 만", "담당 AGENT" 같은 세부 규칙은 각 컨트롤러에서 {@code @PreAuthorize}.</li>
 *   <li>역할 계층: ADMIN &gt; LEAD &gt; AGENT (04 의 "AGENT+", "LEAD+"). CUSTOMER 는 계층 밖.</li>
 *   <li>새 공개 API 가 생기면 04 API 명세 PR 과 함께 백성준에게 CR.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper, RateLimiter rateLimiter,
            @Value("${app.rate-limit.enabled:true}") boolean rateLimitEnabled,
            @Value("${app.proxy-secret}") String proxySecret) throws Exception {
        // 토큰 없음·만료·위조 → 401, 역할 부족 → 403 (ApiResponse 형식)
        AuthenticationEntryPoint unauthorized = (req, res, e) -> writeError(res, jsonMapper, CommonErrorCode.UNAUTHORIZED);
        AccessDeniedHandler forbidden = (req, res, e) -> writeError(res, jsonMapper, CommonErrorCode.FORBIDDEN);
        http
                .csrf(AbstractHttpConfigurer::disable)          // 쿠키는 Refresh(/api/auth, SameSite=Lax)뿐, 나머지는 헤더 토큰
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())                // 아래 CorsConfigurationSource 빈 사용
                .authorizeHttpRequests(auth -> auth
                        // 공개 (04 권한 열 "공개")
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/signup", "/api/auth/login", "/api/auth/refresh",
                                "/api/auth/guest", "/api/auth/password/**", "/api/auth/guest/**",
                                "/api/attachments", "/api/tickets").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/faqs", "/api/faqs/**").permitAll()
                        .requestMatchers("/api/surveys/*").permitAll()
                        .requestMatchers("/ws/**").permitAll()       // STOMP 인증은 StompAuthInterceptor(박민재)
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        // 역할 prefix
                        .requestMatchers("/api/admin/**", "/api/reports/**").hasRole("LEAD")
                        .requestMatchers("/api/console/**", "/api/dashboard/**", "/api/templates/**").hasRole("AGENT")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
        // 요청 제한은 인증보다 먼저 (대입 공격이 토큰 검증 비용까지 쓰지 않게). 테스트는 config/application.yml 에서 끈다
        if (rateLimitEnabled) {
            http.addFilterBefore(new RateLimitFilter(rateLimiter, jsonMapper, proxySecret), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    /** role 클레임 → ROLE_{role} 권한 */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtProvider.ROLE_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("""
                ROLE_ADMIN > ROLE_LEAD
                ROLE_LEAD > ROLE_AGENT
                """);
    }

    @Bean
    public SecretKey jwtSecretKey(JwtProperties properties) {
        return JwtProvider.secretKey(properties.secret());
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** REST 는 Next.js rewrites 로 같은 출처 → CORS 는 직접 호출하는 두 경로만 (docs/02 §2.1) */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.front-origin}") String frontOrigin) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontOrigin));
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/attachments", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }

    private static void writeError(HttpServletResponse res, JsonMapper jsonMapper, ErrorCode code) throws IOException {
        res.setStatus(code.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(res.getOutputStream(), ApiResponse.fail(code, code.message()));
    }
}
