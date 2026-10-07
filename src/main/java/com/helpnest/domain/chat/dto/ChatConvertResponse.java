// @owner PMJ
package com.helpnest.domain.chat.dto;

/** "문의로 남기기" 결과 — 고객에게 안내할 티켓번호 (docs/04 §10) */
public record ChatConvertResponse(String ticketNo) {
}
