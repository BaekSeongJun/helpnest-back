// @owner SSJ
package com.helpnest.infra.mail;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MailLogRepository extends JpaRepository<MailLog, Long> {
}
