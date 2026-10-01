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
 * X-Forwarded-For 를 반영한 값이다. 배포 시 백엔드가 CloudFront/프록시를 통해서만 접근 가능해야
 * 클라이언트가 보낸 가짜 X-Forwarded-For 로 제한을 우회할 수 없다 (신수진 배포 설정).
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
