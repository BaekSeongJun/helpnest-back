// @owner SSJ
package com.helpnest.domain.ai.repository;

import com.helpnest.domain.ai.entity.AiDraft;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiDraftRepository extends JpaRepository<AiDraft, Long> {

    List<AiDraft> findByTicketIdOrderByIdDesc(Long ticketId);
}
