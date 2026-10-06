// @owner BSJ
package com.helpnest.domain.survey.repository;

import com.helpnest.domain.survey.entity.Survey;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SurveyRepository extends JpaRepository<Survey, Long> {

    Optional<Survey> findByTicketId(Long ticketId);

    Optional<Survey> findByToken(String token);

    /**
     * 제출을 한 문장으로 확정한다. 미제출·미만료 조건을 UPDATE 자체에 넣어서 동시에 두 번 눌러도
     * 한 번만 반영된다(0 이면 이미 제출됐거나 그 사이 만료된 것).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Survey s set s.rating = :rating, s.comment = :comment, s.submittedAt = :now "
            + "where s.id = :id and s.submittedAt is null and s.expiresAt > :now")
    int markSubmitted(@Param("id") Long id, @Param("rating") Short rating,
            @Param("comment") String comment, @Param("now") OffsetDateTime now);
}
