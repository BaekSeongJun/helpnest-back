// @owner BSJ
package com.helpnest.global.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 고정 창(fixed window) 요청 제한 (PRD FR-INQ-06).
 * <p>{@link RateLimitFilter} 가 IP 기준으로 쓰고, 본문 값이 필요한 제한은 서비스에서 직접 호출한다.
 * 예) 박민재 TicketService — 비회원 이메일 1시간 5건:
 * <pre>{@code
 * if (!rateLimiter.tryAcquire("ticket-email:" + email.toLowerCase(), 5, Duration.ofHours(1))) {
 *     throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
 * }
 * }</pre>
 * ponytail: 서버 메모리 저장이라 인스턴스별로 따로 센다. 서버를 여러 대로 늘리면 Redis 로 교체.
 */
@Component
public class RateLimiter {

    /** 이 개수를 넘으면 만료된 창을 한 번 청소한다 (공격으로 키가 계속 늘어나는 것 방지) */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long resetAtMillis, int count) {
    }

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return 허용이면 true(이번 요청을 센다), 한도 초과면 false
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> w.resetAtMillis() <= now);
        }
        // compute 는 키 단위로 원자적 → 동시 요청이 한도를 넘겨 통과하지 않는다
        Window updated = windows.compute(key, (k, w) -> {
            if (w == null || w.resetAtMillis() <= now) {
                return new Window(now + window.toMillis(), 1);
            }
            return w.count() > limit ? w : new Window(w.resetAtMillis(), w.count() + 1);
        });
        return updated.count() <= limit;
    }

    /**
     * 세지 않고 한도에 닿았는지만 본다. 실패만 세는 제한(로그인·비회원 인증)에서 검사 전에 쓰고,
     * 실패했을 때 {@link #tryAcquire} 로 센다.
     */
    public boolean isExhausted(String key, int limit) {
        Window w = windows.get(key);
        return w != null && w.resetAtMillis() > clock.millis() && w.count() >= limit;
    }

    /** 다음 창까지 남은 초 (Retry-After 헤더용). 기록이 없으면 0 */
    public long retryAfterSeconds(String key) {
        Window w = windows.get(key);
        if (w == null) {
            return 0;
        }
        return Math.max(0, Duration.ofMillis(w.resetAtMillis() - clock.millis()).toSeconds() + 1);
    }
}
