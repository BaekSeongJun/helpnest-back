// @owner BSJ
package com.helpnest.global.security;

import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스팸·대입 공격 방지용 IP 기준 요청 제한 (docs/04 §7, PRD FR-INQ-06). 초과 시 429 COMMON_TOO_MANY_REQUESTS.
 * <p>인증보다 먼저 실행된다(SecurityConfig). 대상·한도는 아래 RULES 표 하나에서만 관리한다.
 * <p>클라이언트 IP 는 {@link #clientIp} — X-Forwarded-For 에서 위조할 수 없는 위치만 쓴다(back #105 실측).
 * 로그인·비회원 인증은 AuthService 가 계정·티켓번호 기준 실패 제한도 따로 건다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** guestOnly: 로그인 사용자(Authorization 헤더 있음)는 제외 — 비회원 스팸 대상 */
    private record Rule(int limit, Duration window, boolean guestOnly) {
    }

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    /** "METHOD 경로" → 규칙 */
    private static final Map<String, Rule> RULES = Map.of(
            "POST /api/tickets", new Rule(5, TEN_MINUTES, true),
            "POST /api/attachments", new Rule(5, TEN_MINUTES, true),
            "POST /api/auth/login", new Rule(10, TEN_MINUTES, false),
            "POST /api/auth/guest", new Rule(10, TEN_MINUTES, false),      // 조회 비밀번호 대입 방지
            "POST /api/auth/password/reset-request", new Rule(5, TEN_MINUTES, false),
            "POST /api/auth/guest/reset-request", new Rule(5, TEN_MINUTES, false));

    /** 프론트 proxy.ts 가 Amplify 경유 /api 요청에 붙이는 비밀 헤더 */
    static final String PROXY_HEADER = "X-Helpnest-Proxy";

    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper;
    /** 비어 있으면(로컬) 비밀 헤더를 믿지 않는다 */
    private final byte[] proxySecret;

    public RateLimitFilter(RateLimiter rateLimiter, JsonMapper jsonMapper, String proxySecret) {
        this.rateLimiter = rateLimiter;
        this.jsonMapper = jsonMapper;
        this.proxySecret = proxySecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String target = request.getMethod() + " " + request.getRequestURI();
        Rule rule = RULES.get(target);
        boolean member = request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        if (rule == null || (rule.guestOnly() && member)) {
            chain.doFilter(request, response);
            return;
        }

        String key = target + "|" + clientIp(request);
        if (rateLimiter.tryAcquire(key, rule.limit(), rule.window())) {
            chain.doFilter(request, response);
            return;
        }
        CommonErrorCode code = CommonErrorCode.TOO_MANY_REQUESTS;
        response.setStatus(code.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.retryAfterSeconds(key)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code, code.message()));
    }

    /**
     * CloudFront 는 받은 X-Forwarded-For 뒤에 자기가 본 주소를 덧붙이므로 오른쪽 값만 믿을 수 있다.
     * <ul>
     *   <li>CloudFront 직접: {@code [위조…], 클라이언트} → 오른쪽 1번째</li>
     *   <li>Amplify 경유: {@code [위조…], 클라이언트, Amplify CloudFront, Amplify 컴퓨트} → 오른쪽 3번째.
     *       비밀 헤더가 맞을 때만 Amplify 경유로 본다(없거나 틀리면 직접 호출로 보고 오른쪽 1번째).</li>
     * </ul>
     * XFF 가 없으면(로컬 직접 호출) 접속 주소. {@code server.forward-headers-strategy: none} 이어야 XFF 원문이 남는다.
     * ponytail: Amplify 뒤 홉 수(2)는 10/8 실측값. Amplify 구조가 바뀌면 이 숫자만 고친다.
     */
    String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] hops = xff.split(",");
        int fromRight = viaAmplify(request) ? 3 : 1;
        return hops[Math.max(0, hops.length - fromRight)].trim();
    }

    private boolean viaAmplify(HttpServletRequest request) {
        String header = request.getHeader(PROXY_HEADER);
        return proxySecret.length > 0 && header != null
                && MessageDigest.isEqual(proxySecret, header.getBytes(StandardCharsets.UTF_8));
    }
}
