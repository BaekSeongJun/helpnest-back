// @owner SSJ
package com.helpnest.domain.dashboard.dto;

import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 대시보드 기간 필터 (docs/04 §13 ?period=TODAY|7D|30D, FR-DSH-02). 기준 시각은 Asia/Seoul */
public enum Period {
    TODAY, D7, D30;

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 생략하면 TODAY. 모르는 값은 400 */
    public static Period parse(String value) {
        return switch (value == null ? "TODAY" : value) {
            case "TODAY" -> TODAY;
            case "7D" -> D7;
            case "30D" -> D30;
            default -> throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        };
    }

    /** 집계 시작 시각: TODAY=오늘 0시, 7D/30D=now-n일 */
    public OffsetDateTime start(OffsetDateTime now) {
        return switch (this) {
            case TODAY -> todayStart(now);
            case D7 -> now.minusDays(7);
            case D30 -> now.minusDays(30);
        };
    }

    public static OffsetDateTime todayStart(OffsetDateTime now) {
        return now.atZoneSameInstant(SEOUL).toLocalDate().atStartOfDay(SEOUL).toOffsetDateTime();
    }
}
