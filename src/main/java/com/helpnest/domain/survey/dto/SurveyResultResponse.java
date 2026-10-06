// @owner BSJ
package com.helpnest.domain.survey.dto;

import com.helpnest.domain.survey.repository.SurveyResultRepository.ResultRow;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 설문 결과 목록 한 줄 (CS-06). 담당자가 없는 티켓이면 agentName 은 null */
public record SurveyResultResponse(String ticketNo, String customerName, String agentName, int rating,
        String comment, OffsetDateTime submittedAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static SurveyResultResponse from(ResultRow row) {
        return new SurveyResultResponse(row.getTicketNo(), row.getCustomerName(), row.getAgentName(),
                row.getRating(), row.getComment(), OffsetDateTime.ofInstant(row.getSubmittedAt(), SEOUL));
    }
}
