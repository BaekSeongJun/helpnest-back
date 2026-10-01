// @owner SSJ
package com.helpnest.domain.ai.repository;

import com.helpnest.domain.ai.entity.TicketAiResult;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketAiResultRepository extends JpaRepository<TicketAiResult, Long> {

    Optional<TicketAiResult> findByTicketId(Long ticketId);
}
