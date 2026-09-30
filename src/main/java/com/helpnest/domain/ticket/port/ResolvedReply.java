// @owner PMJ
package com.helpnest.domain.ticket.port;

/**
 * AI 답변 초안의 참고 자료로 넘기는 과거 답변(docs/05 §4.1 '과거 답변' 소스).
 *
 * <p>필드가 두 개뿐인 것은 docs/05 §4.2 프롬프트가 {@code 과거답변#{id}: {content}} 형태로만
 * 쓰기 때문이다. 초안 저장 시 {@code AI_DRAFT.reference_refs} 에 어떤 답변을 참고했는지
 * 기록해야 하므로 id 가 필요하다.
 *
 * <p>내부 메모({@code ticket_reply.is_internal=true})는 절대 포함하지 않는다. 고객에게 보내는
 * 초안의 참고 자료로 들어가면 내부 메모가 그대로 고객에게 전달될 수 있다.
 *
 * @param replyId 원본 답변의 reply_id
 * @param content 답변 본문
 */
public record ResolvedReply(Long replyId, String content) {
}
