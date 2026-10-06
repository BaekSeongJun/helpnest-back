// @owner BSJ
package com.helpnest.domain.customer.dto;

import com.helpnest.domain.customer.repository.CustomerHistoryRepository.TicketRow;
import com.helpnest.global.common.PageResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 고객 이력 (CS-02 패널·CS-07). customerKey 는 화면이 전체 보기 링크로 그대로 쓴다.
 * 비회원 이메일은 응답에 싣지 않는다 — 키에 이미 있고, 목록에 다시 노출할 이유가 없다.
 */
public record CustomerHistoryResponse(String customerKey, String customerName, Summary summary,
        PageResponse<Item> tickets) {

    /** avgRating 은 소수 1자리, 응답이 없으면 null. lastTicketAt 은 티켓이 없으면 null */
    public record Summary(long totalCount, Double avgRating, OffsetDateTime lastTicketAt) {
    }

    /** rating 은 설문 미응답이면 null */
    public record Item(Long ticketId, String ticketNo, String title, String status, String category,
            OffsetDateTime createdAt, Integer rating) {

        private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

        public static Item from(TicketRow row) {
            return new Item(row.getTicketId(), row.getTicketNo(), row.getTitle(), row.getStatus(), row.getCategory(),
                    OffsetDateTime.ofInstant(row.getCreatedAt(), SEOUL),
                    row.getRating() == null ? null : row.getRating().intValue());
        }
    }
}
