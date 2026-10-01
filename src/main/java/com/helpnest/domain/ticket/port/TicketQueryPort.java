// @owner PMJ
package com.helpnest.domain.ticket.port;

import java.util.List;

/** 호출자: 백성준, 신수진(AI 초안 컨텍스트) (docs/02 §5.2, docs/05 §3.1·§4.1) */
public interface TicketQueryPort {

    /**
     * 분류·초안 프롬프트 조립용 티켓 요약을 반환한다(docs/05 §3.1 2번).
     *
     * @param ticketId 대상 티켓
     * @return 제목·본문·고객 선택 유형. 티켓이 없으면 예외를 던진다(조용히 null 을 주지 않는다)
     */
    TicketSummary getTicketSummary(Long ticketId);

    /**
     * 같은 유형의 과거 답변을 참고 자료로 반환한다(docs/05 §4.1). RESOLVED·CLOSED 티켓의
     * 답변만 대상이며 만족도 4 이상을 우선한다. 내부 메모는 제외한다.
     *
     * @param category docs/03 §2.1 category 값
     * @param limit    최대 건수(docs/05 §4.1 은 3건을 쓴다)
     * @return 참고할 답변. 없으면 빈 List
     */
    List<ResolvedReply> findResolvedReplies(String category, int limit);
}
