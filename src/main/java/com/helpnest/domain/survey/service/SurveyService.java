// @owner BSJ
package com.helpnest.domain.survey.service;

import com.helpnest.domain.survey.dto.SurveyResponse;
import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.error.SurveyErrorCode;
import com.helpnest.domain.survey.event.SurveySubmittedEvent;
import com.helpnest.domain.survey.repository.SurveyRepository;
import com.helpnest.domain.ticket.port.ResolvedMailInfo;
import com.helpnest.domain.ticket.port.TicketQueryPort;
import com.helpnest.global.error.BusinessException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 발급·만료·조회·제출 (FR-SRV-01~06). 발급·만료의 호출자는 AFTER_COMMIT 비동기 리스너라
 * 주변 트랜잭션이 없고, 그래서 여기서 새로 연다.
 */
@Service
@RequiredArgsConstructor
public class SurveyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SurveyRepository surveyRepository;
    private final TicketQueryPort ticketQueryPort;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 설문 페이지 진입. 티켓번호·제목은 해결 메일용 포트에서 가져온다 — 두 값이 다 들어 있고
     * (getTicketSummary 에는 티켓번호가 없다) 포트를 하나 더 늘리지 않아도 된다.
     */
    @Transactional(readOnly = true)
    public SurveyResponse get(String token) {
        Survey survey = findByToken(token);
        ResolvedMailInfo info = ticketQueryPort.getResolvedMailInfo(survey.getTicketId());
        return new SurveyResponse(info.ticketNo(), info.title(),
                survey.isExpired(OffsetDateTime.now()), survey.getSubmittedAt() != null);
    }

    /**
     * 제출은 1회만. 사전 검사로 정확한 에러를 고르고, 반영은 조건부 UPDATE 로 확정해서
     * 동시 요청이 사전 검사를 함께 통과해도 한 번만 들어간다.
     */
    @Transactional
    public void submit(String token, int rating, String comment) {
        Survey survey = findByToken(token);
        OffsetDateTime now = OffsetDateTime.now();
        if (survey.getSubmittedAt() != null) {
            throw new BusinessException(SurveyErrorCode.ALREADY_SUBMITTED);
        }
        if (survey.isExpired(now)) {
            throw new BusinessException(SurveyErrorCode.EXPIRED);
        }
        String trimmed = comment == null || comment.isBlank() ? null : comment.strip();
        if (surveyRepository.markSubmitted(survey.getId(), (short) rating, trimmed, now) == 0) {
            throw new BusinessException(SurveyErrorCode.ALREADY_SUBMITTED);
        }
        eventPublisher.publishEvent(new SurveySubmittedEvent(survey.getTicketId(), rating));
    }

    private Survey findByToken(String token) {
        return surveyRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.NOT_FOUND));
    }

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
