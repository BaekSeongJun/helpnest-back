// @owner SSJ
package com.helpnest.domain.ai.repository;

import com.helpnest.domain.ai.entity.AiDraft;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * AI-2 초안용 티켓 맥락 조회 (docs/05 §4.1 '대화 맥락', 04 §12 '담당 AGENT').
 *
 * <h2>포트가 아니라 읽기 전용 네이티브 쿼리를 쓰는 이유</h2>
 * {@code TicketQueryPort} 에는 담당자·현재 티켓 답변 조회가 없다. docs/02 §5 읽기 전용 예외에 따라
 * 자기 패키지 안에서 읽기만 하고 쓰기는 하지 않는다. 박민재 {@code MemberNameLookupRepository} 와 같은 방식.
 * 참조 컬럼(PR 본문에도 기재): {@code ticket(ticket_id, agent_id)},
 * {@code ticket_reply(ticket_id, writer_type, content, is_internal, created_at)}.
 *
 * <p>{@code Repository} 마커만 상속해 AiDraft 의 CRUD 가 딸려 오지 않게 한다.
 */
public interface DraftContextRepository extends Repository<AiDraft, Long> {

    /** 티켓이 없으면 empty, 미배정이면 agentId=null 인 행 */
    @Query(value = "select agent_id as \"agentId\" from ticket where ticket_id = :ticketId", nativeQuery = true)
    Optional<TicketAgent> findTicketAgent(@Param("ticketId") Long ticketId);

    /** 최근 공개 답변(내부 메모 제외) — 최신순 */
    @Query(value = """
            select writer_type as "writerType", content as "content"
            from ticket_reply
            where ticket_id = :ticketId and is_internal = false
            order by created_at desc, reply_id desc
            limit :limit
            """, nativeQuery = true)
    List<RecentReply> findRecentPublicReplies(@Param("ticketId") Long ticketId, @Param("limit") int limit);

    interface TicketAgent {
        Long getAgentId();
    }

    interface RecentReply {
        String getWriterType();

        String getContent();
    }
}
