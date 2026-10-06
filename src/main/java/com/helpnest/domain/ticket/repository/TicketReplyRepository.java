// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.port.ResolvedReply;

/**
 * 티켓 답변 저장·조회.
 *
 * <p>S1 에서 조회 메서드를 추가할 때 고객용과 상담원용을 반드시 분리한다. 고객용은
 * {@code isInternal=false} 조건이 빠지면 내부 메모가 그대로 노출되므로, 조건을 호출부의
 * 책임으로 남기지 말고 쿼리 메서드 이름에 못박는 편이 안전하다.
 */
public interface TicketReplyRepository extends JpaRepository<TicketReply, Long> {

    /**
     * <b>고객용</b> 답변 목록 — 내부 메모를 제외한다.
     *
     * <p>조건을 메서드 이름에 못박은 것은 의도다. {@code findByTicketId} 로 전부 읽어 호출부에서
     * 거르는 방식이면 호출부가 하나라도 조건을 빠뜨릴 때 내부 메모가 고객 응답에 섞인다.
     * 이름에 박아 두면 빠뜨릴 방법이 없다({@code TicketReply} 의 "고객 노출 여부" 주석).
     */
    List<TicketReply> findByTicketIdAndIsInternalFalseOrderByCreatedAtAsc(Long ticketId);

    /** 상담원용 — 내부 메모를 포함한 전체 답변 (콘솔 상세 화면 CS-02) */
    List<TicketReply> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    /**
     * AI 답변 초안의 참고 자료가 되는 과거 답변 (docs/05 §4.1,
     * {@code TicketQueryPort.findResolvedReplies}).
     *
     * <p>조건 네 가지가 모두 필요하다 — 같은 유형, 해결·종료된 티켓, <b>내부 메모 제외</b>,
     * 상담원이 쓴 답변. 내부 메모가 섞이면 고객에게 보내는 초안의 참고 자료로 들어가
     * 그대로 전달될 수 있다({@link ResolvedReply} 주석).
     *
     * <p>TODO(PMJ): docs/05 §4.1 은 "만족도 4 이상 우선"도 요구하지만 survey 테이블이
     * 아직 없다(백성준 S2). 테이블이 들어오면 survey 를 LEFT JOIN 해
     * {@code order by survey.rating desc nulls last, r.createdAt desc} 로 바꾼다.
     * 지금은 최신순이며, 이는 참고 자료의 품질 정렬이 빠진 상태라는 뜻이다.
     *
     * @param pageable 개수 제한용. {@code PageRequest.of(0, limit)} 를 넘긴다
     */
    @Query("""
            select new com.helpnest.domain.ticket.port.ResolvedReply(r.id, r.content)
            from TicketReply r, Ticket t
            where r.ticketId = t.id
              and t.category = :category
              and t.status in (
                com.helpnest.domain.ticket.entity.TicketStatus.RESOLVED,
                com.helpnest.domain.ticket.entity.TicketStatus.CLOSED)
              and r.isInternal = false
              and r.writerType = com.helpnest.domain.ticket.entity.WriterType.AGENT
            order by r.createdAt desc
            """)
    List<ResolvedReply> findResolvedReplies(@Param("category") TicketCategory category, Pageable pageable);

    /**
     * 해결 결과 메일에 넣을 <b>가장 최근 상담원 공개 답변</b>
     * ({@code TicketQueryPort.getResolvedMailInfo}, CR #48).
     *
     * <p>조건을 이름에 다 박아 둔 것은 위 고객용 목록과 같은 이유다 — {@code isInternal=false} 가
     * 빠지면 상담원끼리 주고받은 내부 메모가 그대로 고객 메일로 나간다. {@code writerType} 만
     * 인자로 받는 것은 파생 쿼리가 enum 상수를 이름에 담을 수 없어서이며, 호출부는
     * {@code WriterType.AGENT} 한 곳뿐이다.
     *
     * <h2>{@code IdDesc} 를 빼지 말 것</h2>
     * {@code createdAt} 은 {@code @CreationTimestamp} 로 채워지고 시계 해상도 때문에 연속
     * insert 두 건이 <b>같은 값을 받을 수 있다</b>. 그러면 {@code CreatedAtDesc} 만으로는
     * 정렬이 비결정적이 되어 더 오래된 답변이 뽑힌다(TicketQueryPortTest 가 실제로 이걸 잡았다).
     * 운영에서도 상담원이 더블클릭·재시도로 같은 순간에 두 건을 저장하면 고객 메일에 이전
     * 답변이 인용된다. reply_id 는 시퀀스라 단조 증가하므로 동률을 확실히 가른다.
     *
     * @return 공개 답변 없이 해결된 티켓이면 {@code Optional.empty()}
     */
    Optional<TicketReply> findFirstByTicketIdAndWriterTypeAndIsInternalFalseOrderByCreatedAtDescIdDesc(
            Long ticketId, WriterType writerType);
}
