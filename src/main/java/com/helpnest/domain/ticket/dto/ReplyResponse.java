// @owner PMJ
package com.helpnest.domain.ticket.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.helpnest.domain.ticket.entity.WriterType;

/**
 * 답변·내부 메모 한 건. 프론트 {@code TicketTimeline} 이 이 모양을 그대로 받는다.
 *
 * <p>필드 이름을 {@code isInternal} 로 둔 것은 의도다. record 는 컴포넌트 이름을 그대로
 * 직렬화하므로 {@code isInternal} 로 나가지만, 같은 값을 POJO 의 {@code isInternal()} 게터로
 * 두면 Jackson 이 {@code internal} 로 깎아 프론트 타입과 어긋난다.
 *
 * @param writerName GUEST 는 비회원 이름, SYSTEM 은 시스템 표시 문구
 */
public record ReplyResponse(
        Long replyId,
        WriterType writerType,
        String writerName,
        String content,
        boolean isInternal,
        List<TicketAttachmentResponse> attachments,
        OffsetDateTime createdAt) {
}
