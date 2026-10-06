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

    /**
     * 해결 결과 메일에 필요한 티켓 정보를 반환한다(CR #48, PRD FR-SRV-01·02).
     *
     * <p>호출자는 백성준의 {@code SurveyListener} 이며, 받은 값을 신수진의
     * {@code MailSender.sendResolvedMail} 에 넘긴다. 수신자 이름·이메일을 내가 완성해 주지 않는
     * 이유와 답변을 요약하지 않는 이유는 {@link ResolvedMailInfo} 주석에 있다.
     *
     * @param ticketId 대상 티켓
     * @return 메일 발송에 필요한 값. 티켓이 없으면 {@code TICKET_NOT_FOUND} 를 던진다
     *         ({@link #getTicketSummary} 와 같은 방침 — 메일이 조용히 누락되면
     *         고객은 해결 통보를 영구히 받지 못한다)
     */
    ResolvedMailInfo getResolvedMailInfo(Long ticketId);
}
