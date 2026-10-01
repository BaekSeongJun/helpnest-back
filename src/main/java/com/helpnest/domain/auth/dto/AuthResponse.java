// @owner BSJ
package com.helpnest.domain.auth.dto;

import com.helpnest.domain.member.dto.MemberResponse;

/** login·refresh 응답 본문. Refresh 토큰은 본문에 담지 않고 쿠키로만 보낸다. */
public record AuthResponse(String accessToken, MemberResponse member) {
}
