// @owner BSJ
package com.helpnest.domain.member.dto;

import jakarta.validation.constraints.NotNull;

public record AvailabilityRequest(@NotNull(message = "상담 가능 여부를 선택해 주세요.") Boolean available) {
}
