// @owner SSJ
package com.helpnest.infra.mail;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MailLogRepository extends JpaRepository<MailLog, Long> {

    /** AGENT_REPLY 10분 묶음 판단 */
    boolean existsByTicketIdAndMailTypeAndStatusInAndCreatedAtAfter(
            Long ticketId, MailLog.Type mailType, Collection<MailLog.Status> statuses, OffsetDateTime after);

    /** 재시도 대상 (MailRetryScheduler) */
    List<MailLog> findTop50ByStatusAndRetryCountLessThanOrderByIdAsc(MailLog.Status status, int retryCount);
}
