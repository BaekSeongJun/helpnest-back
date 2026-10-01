// @owner BSJ
package com.helpnest.domain.attachment.port;

import com.helpnest.domain.attachment.error.AttachmentErrorCode;
import com.helpnest.domain.attachment.repository.AttachmentRepository;
import com.helpnest.domain.attachment.service.AttachmentService;
import com.helpnest.global.error.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AttachmentAdapter implements AttachmentPort {

    private final AttachmentRepository attachmentRepository;

    /**
     * 호출자 트랜잭션(티켓·답글 생성)에 참여한다. 하나라도 연결 못 하면 예외 → 티켓 생성까지 롤백.
     * 연결 조건(업로더 일치 등)은 {@link AttachmentRepository#linkToTicket} 참고.
     */
    @Override
    @Transactional
    public void linkToTicket(List<Long> attachmentIds, Long ticketId, Long replyId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        Set<Long> ids = new LinkedHashSet<>(attachmentIds);
        if (ids.size() > AttachmentService.MAX_ATTACHMENT_COUNT) {
            throw new BusinessException(AttachmentErrorCode.TOO_MANY);
        }
        if (attachmentRepository.linkToTicket(ids, ticketId, replyId) != ids.size()) {
            throw new BusinessException(AttachmentErrorCode.LINK_DENIED);
        }
    }
}
