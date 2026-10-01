// @owner BSJ
package com.helpnest.domain.attachment.service;

import com.helpnest.domain.attachment.entity.Attachment;
import com.helpnest.domain.attachment.repository.AttachmentRepository;
import com.helpnest.infra.storage.FileStorage;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 업로드만 하고 티켓·답글에 연결되지 않은 첨부(작성 포기 등)를 하루 뒤 지운다.
 * 행을 먼저 지우고(연결된 것은 조건에서 빠짐) 성공한 것만 파일 본체를 삭제한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanAttachmentCleaner {

    static final Duration ORPHAN_AGE = Duration.ofHours(24);

    private final AttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;

    @Scheduled(cron = "${app.attachment.orphan-cleanup-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        int deleted = cleanup(OffsetDateTime.now().minus(ORPHAN_AGE));
        if (deleted > 0) {
            log.info("고아 첨부 {}건 정리", deleted);
        }
    }

    // ponytail: 1회 최대 500건 — 하루 업로드가 이보다 많아지면 반복 실행 또는 주기 단축
    /** @return 삭제한 건수 */
    public int cleanup(OffsetDateTime cutoff) {
        int deleted = 0;
        for (Attachment a : attachmentRepository.findTop500ByTicketIdIsNullAndCreatedAtBefore(cutoff)) {
            if (attachmentRepository.deleteIfOrphan(a.getId()) == 0) {
                continue;
            }
            deleted++;
            try {
                fileStorage.delete(a.getStoredKey());
            } catch (RuntimeException e) {
                // 행은 이미 없으므로 본체만 남는다 — 다음 작업을 막지 않고 기록만
                log.warn("첨부 파일 본체 삭제 실패 key={}", a.getStoredKey(), e);
            }
        }
        return deleted;
    }
}
