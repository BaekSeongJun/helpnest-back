// @owner PMJ
package com.helpnest.domain.sla.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * SLA 정책 수정 (docs/04 §8 PUT).
 *
 * <p>상한을 DB 컬럼에 맞춰 막는다 — {@code warning_ratio} 가 NUMERIC(3,2) 라 소수 세 자리를
 * 보내면 DB 가 조용히 반올림해 저장하고, 화면에는 보낸 값과 다른 숫자가 남는다. 400 으로
 * 돌려주는 편이 "저장했는데 값이 다른" 상태보다 낫다.
 *
 * <p>비율 1.00 은 허용한다 — "임박 알림을 쓰지 않고 초과만 받겠다"는 설정이며, 기한과 임박이
 * 같은 시각이 되어 임박 조회가 {@code firstResponseDueAt >= now} 에서 걸러진다.
 */
public record SlaPolicyUpdateRequest(

        @NotNull(message = "첫 응답 기한(분)을 입력해 주세요.")
        @Min(value = 1, message = "첫 응답 기한은 1분 이상이어야 합니다.")
        Integer responseMinutes,

        @NotNull(message = "임박 비율을 입력해 주세요.")
        @DecimalMin(value = "0.01", message = "임박 비율은 0.01 이상이어야 합니다.")
        @DecimalMax(value = "1.00", message = "임박 비율은 1.00 이하여야 합니다.")
        @Digits(integer = 1, fraction = 2, message = "임박 비율은 소수 두 자리까지만 입력할 수 있습니다.")
        BigDecimal warningRatio) {
}
