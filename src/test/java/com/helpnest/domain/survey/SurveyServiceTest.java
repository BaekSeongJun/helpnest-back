// @owner BSJ
package com.helpnest.domain.survey;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.repository.SurveyRepository;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.repository.TicketRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 발급·재발급·만료 (FR-SRV-01, 06). 마이그레이션(FK·UNIQUE·CHECK)과 엔티티 매핑(ddl-auto=validate)까지
 * 실제 DB 로 확인한다. 매 테스트가 롤백돼 ticket·survey 행이 남지 않는다.
 */
@SpringBootTest
@Transactional
@DisplayName("SurveyService — 설문 발급·재발급·만료")
class SurveyServiceTest {

    @Autowired
    SurveyService surveyService;
    @Autowired
    SurveyRepository surveyRepository;
    @Autowired
    TicketRepository ticketRepository;

    private Long givenTicketId() {
        return ticketRepository.save(Ticket.builder()
                .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .title("설문 테스트")
                .content("본문")
                .channel(TicketChannel.WEB)
                .guestName("김비회원")
                .guestEmail("survey@example.com")
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build()).getId();
    }

    @Test
    @DisplayName("처음 해결되면 설문을 만들고 72시간 뒤 만료로 잡는다")
    void issuesNewSurvey() {
        Long ticketId = givenTicketId();

        Survey survey = surveyService.issueOrReissue(ticketId);

        assertThat(survey.getToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(survey.getExpiresAt()).isEqualTo(survey.getSentAt().plusHours(72));
        assertThat(survey.getSubmittedAt()).isNull();
        assertThat(surveyRepository.findByTicketId(ticketId)).isPresent();
    }

    @Test
    @DisplayName("재해결이면 같은 행에 새 토큰을 발급하고 이전 응답을 지운다")
    void reissuesSameRow() {
        Long ticketId = givenTicketId();
        Survey first = surveyService.issueOrReissue(ticketId);
        String oldToken = first.getToken();
        ReflectionTestUtils.setField(first, "rating", (short) 4);
        ReflectionTestUtils.setField(first, "comment", "좋아요");
        ReflectionTestUtils.setField(first, "submittedAt", OffsetDateTime.now());

        Survey again = surveyService.issueOrReissue(ticketId);

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(surveyRepository.count()).isEqualTo(1);
        assertThat(again.getToken()).isNotEqualTo(oldToken);
        assertThat(again.getRating()).isNull();
        assertThat(again.getComment()).isNull();
        assertThat(again.getSubmittedAt()).isNull();
        assertThat(again.getExpiresAt()).isAfter(OffsetDateTime.now().plusHours(71));
    }

    @Test
    @DisplayName("재문의되면 미제출 설문을 즉시 만료한다")
    void expiresUnsubmitted() {
        Long ticketId = givenTicketId();
        Survey survey = surveyService.issueOrReissue(ticketId);

        surveyService.expire(ticketId);

        assertThat(survey.getExpiresAt()).isBeforeOrEqualTo(OffsetDateTime.now());
    }

    @Test
    @DisplayName("이미 제출된 설문은 재문의로 만료를 바꾸지 않는다")
    void keepsSubmitted() {
        Long ticketId = givenTicketId();
        Survey survey = surveyService.issueOrReissue(ticketId);
        OffsetDateTime expiresAt = survey.getExpiresAt();
        ReflectionTestUtils.setField(survey, "submittedAt", OffsetDateTime.now());

        surveyService.expire(ticketId);

        assertThat(survey.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("설문이 없는 티켓의 만료 요청은 무시한다")
    void expireWithoutSurveyIsNoop() {
        surveyService.expire(givenTicketId());

        assertThat(surveyRepository.count()).isZero();
    }
}
