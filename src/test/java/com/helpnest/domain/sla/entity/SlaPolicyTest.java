// @owner PMJ
package com.helpnest.domain.sla.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.helpnest.domain.ticket.entity.TicketPriority;

/**
 * SLA 기한 계산 단위 테스트(docs/10 §3.4 필수 항목 "SLA 계산").
 *
 * <p>검증 전략: PRD 6.1 표의 값을 <b>문서에서 직접 옮겨 리터럴로 적는다</b>.
 * sla_policy 마이그레이션이나 엔티티의 계산식에서 값을 읽어와 비교하면 계산식이 틀려도
 * 통과하는 동어반복이 되기 때문이다(태스크 2의 TicketStateMachineTest 와 같은 전략).
 */
@DisplayName("SlaPolicy — PRD 6.1 첫 응답 기한 계산")
class SlaPolicyTest {

    /** 기준 접수 시각. 분 단위 계산만 검증하므로 임의의 고정 시각을 쓴다(KST). */
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private static final BigDecimal DEFAULT_RATIO = new BigDecimal("0.80");

    private static SlaPolicy policy(TicketPriority priority, int responseMinutes) {
        return SlaPolicy.builder()
                .priority(priority)
                .responseMinutes(responseMinutes)
                .warningRatio(DEFAULT_RATIO)
                .build();
    }

    @Nested
    @DisplayName("calculateDueAt — 첫 응답 기한")
    class CalculateDueAt {

        /** PRD 6.1 '첫 응답 기한' 열: URGENT 1시간 / HIGH 4시간 / NORMAL 24시간 / LOW 48시간. */
        @ParameterizedTest(name = "{0} 은 {1}분 뒤(= {2}시간)가 기한")
        @CsvSource({
            "URGENT,   60,  1",
            "HIGH,    240,  4",
            "NORMAL, 1440, 24",
            "LOW,    2880, 48"
        })
        @DisplayName("우선순위별 기한이 PRD 6.1 표와 일치한다")
        void 우선순위별_기한(TicketPriority priority, int responseMinutes, int expectedHours) {
            SlaPolicy policy = policy(priority, responseMinutes);

            assertThat(policy.calculateDueAt(CREATED_AT))
                    .isEqualTo(CREATED_AT.plusHours(expectedHours));
        }

        @Test
        @DisplayName("자정을 넘겨도 날짜가 정상적으로 넘어간다")
        void 자정_경과() {
            OffsetDateTime lateNight = OffsetDateTime.of(2026, 10, 2, 23, 30, 0, 0, ZoneOffset.ofHours(9));

            OffsetDateTime dueAt = policy(TicketPriority.URGENT, 60).calculateDueAt(lateNight);

            assertThat(dueAt).isEqualTo(OffsetDateTime.of(2026, 10, 3, 0, 30, 0, 0, ZoneOffset.ofHours(9)));
        }

        @Test
        @DisplayName("기한 계산이 인자로 받은 시각을 바꾸지 않는다")
        void 인자_불변() {
            OffsetDateTime before = CREATED_AT;

            policy(TicketPriority.NORMAL, 1440).calculateDueAt(before);

            assertThat(before).isEqualTo(OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
        }
    }

    @Nested
    @DisplayName("calculateWarningAt — 임박 알림 시각")
    class CalculateWarningAt {

        /** PRD 6.1 '임박 알림(80%)' 열: 48분 / 3시간 12분 / 19시간 12분 / 38시간 24분 경과. */
        @ParameterizedTest(name = "{0} 은 {2}분 경과 시점에 임박 알림")
        @CsvSource({
            "URGENT,   60,   48",
            "HIGH,    240,  192",
            "NORMAL, 1440, 1152",
            "LOW,    2880, 2304"
        })
        @DisplayName("우선순위별 임박 시각이 PRD 6.1 표와 일치한다")
        void 우선순위별_임박_시각(TicketPriority priority, int responseMinutes, int expectedWarningMinutes) {
            SlaPolicy policy = policy(priority, responseMinutes);

            assertThat(policy.calculateWarningAt(CREATED_AT))
                    .isEqualTo(CREATED_AT.plusMinutes(expectedWarningMinutes));
        }

        @Test
        @DisplayName("임박 시각은 항상 기한보다 앞선다")
        void 임박은_기한보다_앞() {
            SlaPolicy policy = policy(TicketPriority.HIGH, 240);

            assertThat(policy.calculateWarningAt(CREATED_AT))
                    .isBefore(policy.calculateDueAt(CREATED_AT));
        }

        @Test
        @DisplayName("분 단위로 떨어지지 않는 비율은 반올림한다")
        void 비율_반올림() {
            // 25분 * 0.33 = 8.25분 -> 8분. 초 단위까지 남기면 스케줄러 주기보다 정밀해져 의미가 없다.
            SlaPolicy policy = SlaPolicy.builder()
                    .priority(TicketPriority.URGENT)
                    .responseMinutes(25)
                    .warningRatio(new BigDecimal("0.33"))
                    .build();

            assertThat(policy.calculateWarningAt(CREATED_AT)).isEqualTo(CREATED_AT.plusMinutes(8));
        }

        @Test
        @DisplayName("비율이 1.00 이면 임박 시각과 기한이 같아진다")
        void 비율_최대() {
            SlaPolicy policy = SlaPolicy.builder()
                    .priority(TicketPriority.NORMAL)
                    .responseMinutes(1440)
                    .warningRatio(new BigDecimal("1.00"))
                    .build();

            assertThat(policy.calculateWarningAt(CREATED_AT)).isEqualTo(policy.calculateDueAt(CREATED_AT));
        }
    }
}
