// @owner PMJ
package com.helpnest.domain.ticket.port;

/**
 * 해결 결과 메일에 필요한 티켓 정보 (CR #48, docs/02 §5.2, PRD FR-SRV-01·02).
 *
 * <p>호출자는 백성준의 {@code SurveyListener} 다. {@code TicketStatusChangedEvent(to=RESOLVED)}
 * 를 받아 신수진의 {@code MailSender.sendResolvedMail} 로 결과 메일과 설문 링크를 보낸다.
 *
 * <h2>{@link TicketSummary} 와 따로 둔 이유</h2>
 * 둘의 용도가 다르다. {@code TicketSummary} 는 LLM 분류 프롬프트 입력이라 본문이 필요하지만
 * 티켓번호·수신자가 필요 없고, 이쪽은 메일 발송이라 그 반대다. 한 record 에 합치면 분류
 * 프롬프트에 비회원 이메일이 따라 들어간다 — 개인정보가 LLM 전송 경로에 섞이는 길을 만드는 셈이다.
 *
 * <h2>수신자를 그대로 넘기지 않는다</h2>
 * 회원 티켓이면 {@code customerId} 만 주고 이름·이메일은 호출자가
 * {@code MemberQueryPort.getMember(customerId)} 로 채운다. MEMBER 는 백성준 소유이므로
 * 회원 정보를 내 포트가 대신 읽어 넘기면 소유 경계를 우회하는 꼴이 된다. 비회원은 member 행이
 * 없어 티켓이 유일한 출처이므로 {@code guestName}·{@code guestEmail} 을 직접 준다.
 *
 * <h2>답변을 가공하지 않는다</h2>
 * {@code lastPublicReply} 는 원문이다. 요약(docs/05 §5 의 LLM 1문장, 실패 시 앞 200자)은
 * 메일을 만드는 쪽 책임이다 — CR #48 의 백성준 코멘트와 신수진 #51 에서
 * {@code ResolvedMailCommand.replySummary} 가 {@code finalReply}(원문)로 바뀐 결정에 맞춘다.
 *
 * @param ticketNo        티켓번호(HN-20261002-000123). 메일 본문·제목에 들어간다
 * @param title           티켓 제목
 * @param customerId      회원 티켓이면 member_id, 비회원이면 {@code null}
 * @param guestName       비회원 이름. 회원 티켓이면 {@code null}
 * @param guestEmail      비회원 이메일. 회원 티켓이면 {@code null}
 * @param lastPublicReply 가장 최근 <b>상담원 공개 답변</b> 본문 원문. 내부 메모와 고객 답글은
 *                        제외한다. 답변 없이 해결된 티켓이면 {@code null} —
 *                        내부 메모만 있는 티켓이 여기 걸리면 상담원끼리 주고받은 메모가 그대로
 *                        고객 메일로 나간다
 */
public record ResolvedMailInfo(String ticketNo, String title, Long customerId,
        String guestName, String guestEmail, String lastPublicReply) {
}
