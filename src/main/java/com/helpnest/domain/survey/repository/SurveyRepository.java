// @owner BSJ
package com.helpnest.domain.survey.repository;

import com.helpnest.domain.survey.entity.Survey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SurveyRepository extends JpaRepository<Survey, Long> {

    Optional<Survey> findByTicketId(Long ticketId);
}
