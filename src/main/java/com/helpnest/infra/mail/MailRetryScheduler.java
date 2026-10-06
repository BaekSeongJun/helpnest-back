// @owner SSJ
package com.helpnest.infra.mail;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * FAILED 메일 재시도 (docs/05 §5): 저장된 제목·본문 그대로 재전송, 최초 1회 + 재시도 최대 3회.
 * 3회 모두 실패하면 FAILED 로 남고 대상에서 빠진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailRetryScheduler {

    static final int MAX_RETRY = 3;

    private final MailLogRepository mailLogRepository;
    private final MailTransport transport;

    // ponytail: fixedDelay 라 단일 인스턴스에서만 중복 없음 — 다중 인스턴스 배포 시 ShedLock 등 분산 락
    @Scheduled(fixedDelayString = "${app.mail.retry-delay:5m}", initialDelayString = "${app.mail.retry-delay:5m}")
    public void retryFailed() {
        for (MailLog mail : mailLogRepository.findTop50ByStatusAndRetryCountLessThanOrderByIdAsc(
                MailLog.Status.FAILED, MAX_RETRY)) {
            try {
                mail.markDelivered(transport.send(mail.getToEmail(), mail.getSubject(), mail.getBody()),
                        OffsetDateTime.now());
                log.info("[mail] retry {} mailId={} type={}", mail.getStatus(), mail.getId(), mail.getMailType());
            } catch (Exception e) {
                mail.retryFailed(e.toString());
                log.warn("[mail] retry FAILED mailId={} type={} retryCount={} cause={}", mail.getId(),
                        mail.getMailType(), mail.getRetryCount(), e.toString());
            }
            mailLogRepository.save(mail);
        }
    }
}
