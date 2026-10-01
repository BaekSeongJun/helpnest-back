// @owner PMJ
package com.helpnest.domain.ticket.dto;

/**
 * 티켓·답글에 붙은 첨부 (docs/04 §3 의 응답 필드와 동일한 이름).
 *
 * <p>백성준의 {@code AttachmentResponse} 를 재사용하지 않고 같은 모양을 이 패키지에 둔 이유:
 * 그 record 는 {@code Attachment} 엔티티에 의존하는 그의 소유 파일이라, 가져다 쓰면 내 응답
 * 계약이 남의 파일 변경에 끌려간다(docs/01 §4.1). 조회는 AttachmentPort 에 읽기 메서드가 없어
 * 이 패키지의 읽기 전용 쿼리로 채운다(docs/02 §5 읽기 전용 예외).
 *
 * @param attachmentId 추측 불가 난수. 비회원 첨부는 이 값을 아는 것이 소유 증명이므로
 *                     화면·URL 에 불필요하게 노출하지 않는다(docs/04 §3).
 */
public record TicketAttachmentResponse(Long attachmentId, String originalName, long size) {
}
