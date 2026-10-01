// @owner BSJ
package com.helpnest.domain.auth.dto;

/** Guest 토큰은 프론트 메모리에만 두고 Authorization: Bearer 로 보낸다. expiresIn 은 초 */
public record GuestTokenResponse(String guestToken, Long ticketId, long expiresIn) {
}
