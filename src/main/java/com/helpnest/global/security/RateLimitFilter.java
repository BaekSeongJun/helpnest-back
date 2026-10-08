// @owner BSJ
package com.helpnest.global.security;

import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스팸·대입 공격 방지용 IP 기준 요청 제한 (docs/04 §7, PRD FR-INQ-06). 초과 시 429 COMMON_TOO_MANY_REQUESTS.
 * <p>인증보다 먼저 실행된다(SecurityConfig). 대상·한도는 아래 RULES 표 하나에서만 관리한다.
 * <p>클라이언트 IP 는 {@code getRemoteAddr()} — {@code server.forward-headers-strategy: framework} 가
 * X-Forwarded-For 의 가장 왼쪽 값을 반영한 값이다. CloudFront 는 클라이언트가 보낸 XFF 를 지우지 않고 덧붙이므로
 * 위조 XFF 로 이 IP 제한은 우회된다(back #105, 실측 후 신뢰할 위치로 교체 예정).
 * 그래서 로그인·비회원 인증은 AuthService 가 계정·티켓번호 기준 실패 제한을 따로 건다.
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

    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper;

    public RateLimitFilter(RateLimiter rateLimiter, JsonMapper jsonMapper) {
        this.rateLimiter = rateLimiter;
        this.jsonMapper = jsonMapper;
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

        String key = target + "|" + request.getRemoteAddr();
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
}
