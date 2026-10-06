// @owner BSJ
package com.helpnest.domain.survey.service;

import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.repository.SurveyRepository;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 발급·만료 (FR-SRV-01, 06). 호출자는 AFTER_COMMIT 비동기 리스너라 주변 트랜잭션이 없고,
 * 그래서 여기서 새로 연다.
 */
@Service
@RequiredArgsConstructor
public class SurveyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SurveyRepository surveyRepository;

    /** 해결 시 호출. 처음이면 생성, 이미 있으면(재해결) 같은 행의 토큰·기간을 새로 발급하고 응답을 지운다 */
    @Transactional
    public Survey issueOrReissue(Long ticketId) {
        OffsetDateTime now = OffsetDateTime.now();
        String token = newToken();
        return surveyRepository.findByTicketId(ticketId)
                .map(survey -> {
                    survey.reissue(token, now);
                    return survey;
                })
                .orElseGet(() -> surveyRepository.save(new Survey(ticketId, token, now)));
    }

    /** 재문의 시 호출. 설문이 없거나 이미 제출됐으면 아무 일도 하지 않는다 */
    @Transactional
    public void expire(Long ticketId) {
        surveyRepository.findByTicketId(ticketId).ifPresent(survey -> survey.expire(OffsetDateTime.now()));
    }

    /** 32바이트 → 43자(URL-safe, 패딩 없음). 컬럼 길이 64 이내 */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
