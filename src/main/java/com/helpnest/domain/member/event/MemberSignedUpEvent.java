// @owner BSJ
package com.helpnest.domain.member.event;

/**
 * 이메일 인증이 필요한 가입(PENDING)이 저장됐을 때 발행한다(docs/02 §5, back #117).
 * 구독자: 신수진 이메일 인증 — 인증 링크 토큰을 발급해 메일로 보낸다.
 * 발행: {@code AuthService.signup} — 가입 트랜잭션 안에서 발행하므로 구독자는 AFTER_COMMIT 으로 받는다.
 * 같은 이메일로 인증 전에 다시 가입해도 발행한다(링크 재발송). 인증을 끈 환경(바로 ACTIVE)에서는 발행하지 않는다.
 *
 * @param memberId 가입한 member_id
 * @param email    인증 메일을 받을 주소
 * @param name     메일 인사말용 이름
 */
public record MemberSignedUpEvent(Long memberId, String email, String name) {
}
