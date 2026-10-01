// @owner PMJ
package com.helpnest.domain.ticket.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 티켓번호 형식 단위 테스트 (FR-INQ-03).
 *
 * <p>DB 시퀀스를 쓰는 {@code generate} 대신 순수 함수 {@code format} 을 검증한다.
 * 일련번호 증가는 DB 가 보장하는 성질이라 {@link TicketNoGeneratorDbTest} 에서 확인한다.
 *
 * <p>기대값은 docs/03 §3.2 의 {@code ticket_no} 주석(HN-20261002-000123)에서 직접 옮겼다.
 */
@DisplayName("TicketNoGenerator — 티켓번호 형식")
class TicketNoGeneratorTest {

    /** KST 2026-10-02 10:30 */
    private static final OffsetDateTime KST_MORNING = OffsetDateTime.parse("2026-10-02T10:30:00+09:00");

    @Test
    @DisplayName("HN-YYYYMMDD-NNNNNN 형식으로 만든다")
    void followsDocumentedFormat() {
        assertThat(TicketNoGenerator.format(KST_MORNING, 123)).isEqualTo("HN-20261002-000123");
    }

    @Test
    @DisplayName("ticket_no 컬럼 길이(VARCHAR 20) 안에 들어온다")
    void fitsColumnLength() {
        assertThat(TicketNoGenerator.format(KST_MORNING, 1)).hasSizeLessThanOrEqualTo(20);
        assertThat(TicketNoGenerator.format(KST_MORNING, 99_999_999L)).hasSizeLessThanOrEqualTo(20);
    }

    @Nested
    @DisplayName("일련번호 자리수")
    class SequenceDigits {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({"1,HN-20261002-000001", "123,HN-20261002-000123", "999999,HN-20261002-999999"})
        @DisplayName("6자리까지는 0으로 채운다")
        void padsToSixDigits(long sequence, String expected) {
            assertThat(TicketNoGenerator.format(KST_MORNING, sequence)).isEqualTo(expected);
        }

        @Test
        @DisplayName("999,999 를 넘으면 자르지 않고 7자리로 늘린다 — 잘라서 접으면 같은 날 번호가 겹친다")
        void doesNotTruncateBeyondSixDigits() {
            assertThat(TicketNoGenerator.format(KST_MORNING, 1_000_001L))
                    .isEqualTo("HN-20261002-1000001");
        }
    }

    @Nested
    @DisplayName("날짜 부분은 Asia/Seoul 기준")
    class SeoulDate {

        @Test
        @DisplayName("UTC 14:59 는 아직 KST 같은 날")
        void beforeMidnightInSeoul() {
            assertThat(TicketNoGenerator.format(OffsetDateTime.parse("2026-10-02T14:59:59Z"), 1))
                    .isEqualTo("HN-20261002-000001");
        }

        @Test
        @DisplayName("UTC 15:00 은 KST 로 다음 날 00:00 이라 날짜가 넘어간다")
        void afterMidnightInSeoul() {
            assertThat(TicketNoGenerator.format(OffsetDateTime.parse("2026-10-02T15:00:00Z"), 1))
                    .isEqualTo("HN-20261003-000001");
        }

        @Test
        @DisplayName("같은 순간이면 어느 오프셋으로 들어와도 같은 번호")
        void offsetIndependent() {
            String fromUtc = TicketNoGenerator.format(OffsetDateTime.parse("2026-10-02T01:30:00Z"), 7);
            String fromKst = TicketNoGenerator.format(OffsetDateTime.parse("2026-10-02T10:30:00+09:00"), 7);
            assertThat(fromUtc).isEqualTo(fromKst);
        }
    }
}
