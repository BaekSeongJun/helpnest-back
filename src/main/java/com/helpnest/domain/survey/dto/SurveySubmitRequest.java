// @owner BSJ
package com.helpnest.domain.survey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 설문 제출. 의견은 선택이며 DB 컬럼 길이(1,000자)와 맞춘다 */
public record SurveySubmitRequest(
        @NotNull(message = "별점을 선택해 주세요.")
        @Min(value = 1, message = "별점은 1~5 사이로 선택해 주세요.")
        @Max(value = 5, message = "별점은 1~5 사이로 선택해 주세요.")
        Integer rating,

        @Size(max = 1000, message = "의견은 1,000자 이하로 입력해 주세요.")
        String comment) {
}
