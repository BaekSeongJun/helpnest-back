// @owner PMJ
package com.helpnest.domain.ticket.port;

/**
 * 다른 도메인에 넘기는 티켓 요약. LLM 분류 프롬프트를 조립하는 데 필요한 값만 담는다
 * (docs/05 §3.1 2번, §3.2 User 프롬프트의 {@code categoryHint}·{@code title}·{@code maskedContent}).
 *
 * <p><b>본문은 마스킹하지 않은 원문이다.</b> 개인정보 마스킹은 프롬프트를 조립하는 신수진 쪽
 * 책임이고(docs/05 §3.2 가 {@code maskedContent} 를 요구한다), 마스킹 규칙이 LLM 전송용과
 * 화면 표시용으로 다를 수 있어 포트가 미리 가공하면 호출자가 원문을 되찾을 수 없다.
 *
 * <p>비회원 이메일·조회 비밀번호 해시는 분류에 필요하지 않으므로 담지 않는다.
 *
 * @param title        티켓 제목
 * @param content      티켓 본문 원문
 * @param categoryHint 고객이 접수 시 선택한 유형. LLM 은 참고만 하고 본문을 우선한다(docs/05 §3.2).
 *                     분류 전 기본값이면 ETC 다.
 */
public record TicketSummary(String title, String content, String categoryHint) {
}
