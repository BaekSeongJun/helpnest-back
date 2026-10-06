// @owner BSJ
package com.helpnest.domain.survey.dto;

/** 설문 페이지 진입용 (04 §5). 만료·제출 여부로 화면이 폼/안내를 가른다 */
public record SurveyResponse(String ticketNo, String title, boolean expired, boolean submitted) {
}
