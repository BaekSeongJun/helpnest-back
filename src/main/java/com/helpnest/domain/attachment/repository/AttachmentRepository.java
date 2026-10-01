// @owner BSJ
package com.helpnest.domain.attachment.repository;

import com.helpnest.domain.attachment.entity.Attachment;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ticket·ticket_reply(박민재) 는 읽기만 한다 — docs/02 §5 읽기 전용 예외.
 * 참조 컬럼: ticket.ticket_id, ticket.customer_id, ticket_reply.reply_id, ticket_reply.ticket_id,
 * ticket_reply.writer_id, ticket_reply.is_internal
 */
public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    /**
     * 아직 연결 안 된(ticket_id NULL) 첨부 중 업로더가 티켓 작성자(답글이면 답글 작성자)와 같은 것만 연결.
     * 비회원은 양쪽 모두 NULL 이라 IS NOT DISTINCT FROM 으로 비교한다. 한 문장이라 동시 연결 경합이 없다.
     *
     * @return 연결된 행 수. 요청 개수와 다르면 호출자가 예외로 롤백한다
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE attachment SET ticket_id = :ticketId, reply_id = :replyId
            WHERE attachment_id IN (:ids) AND ticket_id IS NULL
              AND uploaded_by IS NOT DISTINCT FROM (
                CASE WHEN CAST(:replyId AS BIGINT) IS NULL
                  THEN (SELECT t.customer_id FROM ticket t WHERE t.ticket_id = :ticketId)
                  ELSE (SELECT r.writer_id FROM ticket_reply r
                        WHERE r.reply_id = :replyId AND r.ticket_id = :ticketId)
                END)
            """, nativeQuery = true)
    int linkToTicket(@Param("ids") Collection<Long> ids, @Param("ticketId") Long ticketId,
            @Param("replyId") Long replyId);

    /** 비회원 티켓이면 NULL */
    @Query(value = "SELECT customer_id FROM ticket WHERE ticket_id = :ticketId", nativeQuery = true)
    Long findTicketCustomerId(@Param("ticketId") Long ticketId);

    @Query(value = "SELECT COALESCE(BOOL_OR(is_internal), FALSE) FROM ticket_reply WHERE reply_id = :replyId",
            nativeQuery = true)
    boolean isInternalReply(@Param("replyId") Long replyId);
}
