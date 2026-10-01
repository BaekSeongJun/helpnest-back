// @owner BSJ
package com.helpnest.domain.attachment.port;

import java.util.List;

/** 호출자: 박민재(티켓·답글 생성) (docs/02 §5.2) */
public interface AttachmentPort {

    /** 업로드만 된(ticket_id NULL) 첨부를 티켓/답글에 연결. 답글이 아니면 replyId = null */
    void linkToTicket(List<Long> attachmentIds, Long ticketId, Long replyId);
}
