// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.ticket.entity.Ticket;

/**
 * 답글에 붙은 첨부 조회.
 *
 * <h2>포트가 아니라 읽기 전용 네이티브 쿼리를 쓰는 이유</h2>
 * {@code AttachmentPort} 에는 쓰기({@code linkToTicket})만 있고 읽기 메서드가 없다.
 * {@code TicketAttachmentResponse} 주석이 이미 같은 판단을 적어 두었다 — docs/02 §5 읽기 전용
 * 예외를 적용하고 참조 컬럼을 PR 본문에 적는다:
 * {@code attachment(attachment_id, original_name, size_bytes, reply_id, created_at)}.
 *
 * <p>JPQL 이 아닌 네이티브 SQL 인 이유는 JPQL 이면 백성준의 {@code Attachment} 엔티티를
 * import 해야 하는데 docs/10 §3.3 이 이를 금지하기 때문이다({@code LeadLookupRepository},
 * {@code MemberNameLookupRepository} 와 같은 판단).
 */
public interface TicketAttachmentLookupRepository extends Repository<Ticket, Long> {

    /**
     * 답글 1건에 연결된 첨부. 올린 순서대로 돌려준다.
     *
     * <p>별칭에 따옴표를 쓴 것은 PostgreSQL 이 따옴표 없는 식별자를 소문자로 접기 때문이다 —
     * 인터페이스 프로젝션은 getter 이름으로 컬럼을 찾으므로 대소문자가 보존돼야 한다.
     */
    @Query(value = """
            select attachment_id as "attachmentId",
                   original_name as "originalName",
                   size_bytes as "size",
                   reply_id as "replyId"
            from attachment
            where reply_id = :replyId
            order by created_at
            """, nativeQuery = true)
    List<AttachmentRow> findByReplyId(@Param("replyId") Long replyId);

    /**
     * 티켓에 달린 첨부 전부 — 본문 첨부({@code reply_id IS NULL})와 답글 첨부를 한 번에 읽는다.
     *
     * <p>상세 화면은 본문 첨부와 답글별 첨부를 모두 보여 주므로, 답글 수만큼 쿼리를 날리는
     * 대신(N+1) 한 번에 읽고 {@code replyId} 로 묶는다.
     */
    @Query(value = """
            select attachment_id as "attachmentId",
                   original_name as "originalName",
                   size_bytes as "size",
                   reply_id as "replyId"
            from attachment
            where ticket_id = :ticketId
            order by created_at
            """, nativeQuery = true)
    List<AttachmentRow> findByTicketId(@Param("ticketId") Long ticketId);

    /** 첨부 1건의 표시 정보. {@code TicketAttachmentResponse} 로 옮기기 위한 읽기 프로젝션이다. */
    interface AttachmentRow {

        Long getAttachmentId();

        String getOriginalName();

        long getSize();

        /** 답글 첨부면 그 답글의 reply_id, 티켓 본문 첨부면 null */
        Long getReplyId();
    }
}
