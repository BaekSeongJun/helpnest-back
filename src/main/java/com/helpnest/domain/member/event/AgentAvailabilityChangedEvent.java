// @owner BSJ
package com.helpnest.domain.member.event;

/**
 * 상담원의 상담 가능(available) 값이 실제로 바뀌었을 때 발행한다(docs/02 §5, CR #90).
 * 구독자: 박민재 채팅 대기열 — available 이 true 가 되면 대기 중 고객을 즉시 연결한다(docs/02 §6).
 * 발행: {@code MemberService.updateAvailability} — 토글 트랜잭션 안에서 발행하므로 구독자는 AFTER_COMMIT 으로 받는다.
 * 같은 값으로 다시 토글하면 발행하지 않는다.
 *
 * @param memberId  상담원 member_id
 * @param available 바뀐 뒤의 값
 */
public record AgentAvailabilityChangedEvent(Long memberId, boolean available) {
}
