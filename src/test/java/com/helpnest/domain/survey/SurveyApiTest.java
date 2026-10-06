// @owner BSJ
package com.helpnest.domain.survey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.helpnest.domain.survey.entity.Survey;
import com.helpnest.domain.survey.event.SurveySubmittedEvent;
import com.helpnest.domain.survey.repository.SurveyRepository;
import com.helpnest.domain.survey.service.SurveyService;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.repository.TicketRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설문 조회·제출 API (FR-SRV-02, 03). 인증 헤더 없이 보안 필터 체인을 그대로 통과시켜
 * permitAll 도 함께 확인한다. 매 테스트가 롤백돼 행이 남지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
@DisplayName("설문 API — 조회·제출")
class SurveyApiTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    SurveyService surveyService;
    @Autowired
    SurveyRepository surveyRepository;
    @Autowired
    TicketRepository ticketRepository;

    Long ticketId;
    String ticketNo;
    String token;

    @BeforeEach
    void setUp() {
        ticketNo = "HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq());
        ticketId = ticketRepository.save(Ticket.builder()
                .ticketNo(ticketNo)
                .title("설문 API 테스트")
                .content("본문")
                .channel(TicketChannel.WEB)
                .guestName("김비회원")
                .guestEmail("survey-api@example.com")
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                .build()).getId();
        token = surveyService.issueOrReissue(ticketId).getToken();
    }

    private ResultActions submit(String t, String json) throws Exception {
        return mockMvc.perform(post("/api/surveys/" + t).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("조회 — 티켓번호·제목과 만료·제출 여부를 돌려준다 (로그인 불필요)")
    void getSurvey() throws Exception {
        mockMvc.perform(get("/api/surveys/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketNo").value(ticketNo))
                .andExpect(jsonPath("$.data.title").value("설문 API 테스트"))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.submitted").value(false));
    }

    @Test
    @DisplayName("없는 토큰은 404 SURVEY_NOT_FOUND")
    void unknownToken() throws Exception {
        mockMvc.perform(get("/api/surveys/no-such-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SURVEY_NOT_FOUND"));
        submit("no-such-token", "{\"rating\":5}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SURVEY_NOT_FOUND"));
    }

    @Test
    @DisplayName("제출하면 저장하고 SurveySubmittedEvent 를 발행한다")
    void submitOk() throws Exception {
        submit(token, "{\"rating\":4,\"comment\":\"  친절했어요  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Survey saved = surveyRepository.findByToken(token).orElseThrow();
        assertThat(saved.getRating()).isEqualTo((short) 4);
        assertThat(saved.getComment()).isEqualTo("친절했어요");
        assertThat(saved.getSubmittedAt()).isNotNull();
        assertThat(events.stream(SurveySubmittedEvent.class))
                .containsExactly(new SurveySubmittedEvent(ticketId, 4));
        mockMvc.perform(get("/api/surveys/" + token))
                .andExpect(jsonPath("$.data.submitted").value(true));
    }

    @Test
    @DisplayName("의견을 비우면 null 로 저장한다")
    void blankCommentBecomesNull() throws Exception {
        submit(token, "{\"rating\":5,\"comment\":\"   \"}").andExpect(status().isOk());

        assertThat(surveyRepository.findByToken(token).orElseThrow().getComment()).isNull();
    }

    @Test
    @DisplayName("두 번째 제출은 409 SURVEY_ALREADY_SUBMITTED, 이벤트도 한 번뿐")
    void secondSubmit() throws Exception {
        submit(token, "{\"rating\":3}").andExpect(status().isOk());

        submit(token, "{\"rating\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SURVEY_ALREADY_SUBMITTED"));
        assertThat(surveyRepository.findByToken(token).orElseThrow().getRating()).isEqualTo((short) 3);
        assertThat(events.stream(SurveySubmittedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("만료된 설문은 410 SURVEY_EXPIRED 이고 조회에는 expired=true")
    void expired() throws Exception {
        surveyService.expire(ticketId);

        mockMvc.perform(get("/api/surveys/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expired").value(true));
        submit(token, "{\"rating\":5}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("SURVEY_EXPIRED"));
        assertThat(events.stream(SurveySubmittedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("재해결로 재발급되면 옛 링크는 404, 새 링크로 다시 제출할 수 있다")
    void reissuedLink() throws Exception {
        submit(token, "{\"rating\":2}").andExpect(status().isOk());
        String newToken = surveyService.issueOrReissue(ticketId).getToken();

        submit(token, "{\"rating\":5}").andExpect(status().isNotFound());
        submit(newToken, "{\"rating\":5}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("별점이 없거나 범위를 벗어나거나 의견이 너무 길면 400")
    void invalidInput() throws Exception {
        submit(token, "{\"comment\":\"별점 없음\"}").andExpect(status().isBadRequest());
        submit(token, "{\"rating\":0}").andExpect(status().isBadRequest());
        submit(token, "{\"rating\":6}").andExpect(status().isBadRequest());
        submit(token, "{\"rating\":5,\"comment\":\"" + "가".repeat(1001) + "\"}").andExpect(status().isBadRequest());

        assertThat(surveyRepository.findByToken(token).orElseThrow().getSubmittedAt()).isNull();
    }
}
