// @owner BSJ
package com.helpnest.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    /** 시각을 테스트가 직접 움직이는 시계 */
    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    @DisplayName("isExhausted 는 세지 않고 한도 도달만 본다. 창이 지나면 풀린다")
    void isExhausted() {
        MovableClock clock = new MovableClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.isExhausted("k", 3)).isFalse();
            limiter.tryAcquire("k", 3, WINDOW);
        }
        assertThat(limiter.isExhausted("k", 3)).isTrue();
        clock.now = clock.now.plus(WINDOW);
        assertThat(limiter.isExhausted("k", 3)).isFalse();
    }

    @Test
    @DisplayName("한도까지 허용, 한도+1번째부터 거부")
    void limit() {
        RateLimiter limiter = new RateLimiter(new MovableClock());
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, WINDOW)).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isFalse();
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isFalse();
    }

    @Test
    @DisplayName("창이 지나면 다시 허용, 남은 시간은 Retry-After 로")
    void windowReset() {
        MovableClock clock = new MovableClock();
        RateLimiter limiter = new RateLimiter(clock);
        IntStream.range(0, 6).forEach(i -> limiter.tryAcquire("k", 5, WINDOW));
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isFalse();

        clock.now = clock.now.plus(Duration.ofMinutes(4));
        assertThat(limiter.retryAfterSeconds("k")).isEqualTo(Duration.ofMinutes(6).toSeconds() + 1);

        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(limiter.tryAcquire("k", 5, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("키(IP·경로)마다 따로 센다")
    void independentKeys() {
        RateLimiter limiter = new RateLimiter(new MovableClock());
        IntStream.range(0, 5).forEach(i -> limiter.tryAcquire("ip-1", 5, WINDOW));
        assertThat(limiter.tryAcquire("ip-1", 5, WINDOW)).isFalse();
        assertThat(limiter.tryAcquire("ip-2", 5, WINDOW)).isTrue();
    }

    @Test
    @DisplayName("동시 요청 100건이어도 정확히 한도만큼만 통과")
    void concurrent() throws Exception {
        RateLimiter limiter = new RateLimiter(new MovableClock());
        AtomicInteger allowed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 100; i++) {
                pool.submit(() -> {
                    if (limiter.tryAcquire("k", 5, WINDOW)) {
                        allowed.incrementAndGet();
                    }
                });
            }
        }
        assertThat(allowed).hasValue(5);
    }
}
