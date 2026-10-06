// @owner BSJ
package com.helpnest.domain.survey.dto;

import java.util.Map;

/**
 * 설문 요약 카드 (CS-06). 단위는 대시보드와 같다 — responseRate 0~100(%, 소수 1자리), avgRating 소수 1자리.
 * 발송이 없으면 responseRate, 응답이 없으면 avgRating 은 null. distribution 은 1~5 키가 항상 있다.
 */
public record SurveySummaryResponse(long sent, long responded, Double responseRate, Double avgRating,
        Map<Integer, Long> distribution) {
}
