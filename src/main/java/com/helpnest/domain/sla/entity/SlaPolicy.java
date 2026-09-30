// @owner PMJ
package com.helpnest.domain.sla.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

import org.hibernate.annotations.UpdateTimestamp;

import com.helpnest.domain.ticket.entity.TicketPriority;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 우선순위별 첫 응답 기한 정책(PRD 6.1). 기본 4행은 마이그레이션이 넣는다.
 *
 * <p><b>PK 가 자동 생성 id 가 아니라 {@link TicketPriority} 자체</b>이다. 우선순위마다
 * 정책이 정확히 하나만 존재해야 하고 TICKET.priority 가 이 테이블을 FK 로 참조하기 때문이다.
 * 따라서 이 테이블은 행이 추가·삭제되는 테이블이 아니라 4행이 고정된 설정 테이블이며,
 * ADMIN 의 정책 수정(PRD 6.1)은 기존 행의 UPDATE 로 처리한다(S1 이후).
 *
 * <p>created_at 컬럼이 없어 {@code BaseTimeEntity} 를 상속하지 않고 updatedAt 만 직접 둔다.
 */
@Getter
@Entity
@Table(name = "sla_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlaPolicy {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketPriority priority;

    /** 접수 시각부터 첫 응답까지 허용되는 분. URGENT 60 / HIGH 240 / NORMAL 1440 / LOW 2880. */
    @Column(nullable = false)
    private int responseMinutes;

    /** 임박 알림을 보낼 경과 비율. 기본 0.80 이며 NUMERIC(3,2) 이므로 소수 두 자리까지만 유효하다. */
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal warningRatio;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Builder
    private SlaPolicy(TicketPriority priority, int responseMinutes, BigDecimal warningRatio) {
        this.priority = priority;
        this.responseMinutes = responseMinutes;
        this.warningRatio = warningRatio;
    }

    /**
     * 첫 응답 기한을 계산한다. TICKET.first_response_due_at 에 넣는 값이다.
     *
     * <p>PRD 6.1 의 {@code created_at + response_minutes * INTERVAL '1 minute'} 을 옮긴 것으로,
     * 영업시간·휴일 제외는 범위 외이므로 단순 경과 시간으로 계산한다.
     *
     * @param createdAt 티켓 접수 시각
     * @return 첫 응답 기한
     */
    public OffsetDateTime calculateDueAt(OffsetDateTime createdAt) {
        return createdAt.plusMinutes(responseMinutes);
    }

    /**
     * 임박 알림을 보낼 시각을 계산한다. 기한의 {@link #warningRatio} 만큼 경과한 시점이다.
     * 기본 0.80 기준 URGENT 48분 / HIGH 3시간 12분 / NORMAL 19시간 12분 / LOW 38시간 24분(PRD 6.1).
     *
     * <p>비율을 곱한 결과는 분 단위로 반올림한다. 초 단위까지 남기면 스케줄러의
     * 폴링 주기보다 정밀해져 의미가 없기 때문이다.
     *
     * @param createdAt 티켓 접수 시각
     * @return 임박 알림 시각
     */
    public OffsetDateTime calculateWarningAt(OffsetDateTime createdAt) {
        long warningMinutes = warningRatio
                .multiply(BigDecimal.valueOf(responseMinutes))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        return createdAt.plusMinutes(warningMinutes);
    }
}
