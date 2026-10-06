// @owner BSJ
package com.helpnest.domain.survey.service;

import com.helpnest.domain.survey.dto.SurveyResultResponse;
import com.helpnest.domain.survey.dto.SurveySummaryResponse;
import com.helpnest.domain.survey.repository.SurveyResultRepository;
import com.helpnest.domain.survey.repository.SurveyResultRepository.SummaryRow;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설문 결과 목록·요약 (FR-SRV-05). 권한별 범위(AGENT=본인)는 호출자(컨트롤러)가 agentId 로 정한다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyResultService {

    /** 화면의 "날짜"는 한국 날짜다. UTC 로 자르면 자정 전후 응답이 하루 어긋난다 */
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SurveyResultRepository resultRepository;

    /**
     * 조회 조건. 모두 선택이며 from·to 는 양끝 날짜를 포함한다(발송일 기준).
     * agentId 는 호출자가 이미 권한에 맞게 정한 값이다.
     */
    public record Filter(LocalDate from, LocalDate to, Integer rating, Long agentId, TicketCategory category) {
    }

    public PageResponse<SurveyResultResponse> list(Filter f, Pageable pageable) {
        validate(f);
        return PageResponse.from(resultRepository
                .findResults(start(f.from()), endExclusive(f.to()), f.rating() == null ? null : f.rating().shortValue(),
                        f.agentId(), categoryName(f), pageable)
                .map(SurveyResultResponse::from));
    }

    /** 별점 필터는 무시한다 — 분포가 곧 별점 축이라 걸러 버리면 응답률이 왜곡된다 */
    public SurveySummaryResponse summary(Filter f) {
        validate(f);
        SummaryRow row = resultRepository.summarize(start(f.from()), endExclusive(f.to()), f.agentId(),
                categoryName(f));

        Map<Integer, Long> distribution = new LinkedHashMap<>();
        distribution.put(1, row.getR1());
        distribution.put(2, row.getR2());
        distribution.put(3, row.getR3());
        distribution.put(4, row.getR4());
        distribution.put(5, row.getR5());
        Double responseRate = row.getSent() == 0 ? null : round1(row.getResponded() * 100.0 / row.getSent());
        Double avgRating = row.getAvgRating() == null ? null : round1(row.getAvgRating());
        return new SurveySummaryResponse(row.getSent(), row.getResponded(), responseRate, avgRating, distribution);
    }

    private static void validate(Filter f) {
        if (f.rating() != null && (f.rating() < 1 || f.rating() > 5)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "별점은 1~5 사이로 선택해 주세요.");
        }
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "시작일이 종료일보다 늦을 수 없습니다.");
        }
    }

    private static OffsetDateTime start(LocalDate from) {
        return from == null ? null : from.atStartOfDay(SEOUL).toOffsetDateTime();
    }

    /** 종료일 당일을 포함하려고 다음 날 0시 미만으로 자른다 */
    private static OffsetDateTime endExclusive(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime();
    }

    private static String categoryName(Filter f) {
        return f.category() == null ? null : f.category().name();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
