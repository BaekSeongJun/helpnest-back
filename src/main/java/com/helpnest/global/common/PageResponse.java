// @owner BSJ
package com.helpnest.global.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 페이지 응답 형식 (docs/04 §1.1). Spring {@link Page} 를 그대로 내보내지 않고 이 형식으로 변환한다.
 * 사용: {@code ApiResponse.ok(PageResponse.from(page.map(TicketResponse::from)))}
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
