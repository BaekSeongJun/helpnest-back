// @owner BSJ
package com.helpnest.domain.attachment.port;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// ponytail: S0 스텁 — ATTACHMENT 테이블(S1)이 생기면 실제 연결로 교체
@Slf4j
@Component
public class AttachmentAdapter implements AttachmentPort {

    @Override
    public void linkToTicket(List<Long> attachmentIds, Long ticketId, Long replyId) {
        log.debug("[stub] linkToTicket ticketId={} count={}", ticketId, attachmentIds.size());
    }
}
