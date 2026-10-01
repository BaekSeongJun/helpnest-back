// @owner BSJ
package com.helpnest.domain.faq.port;

import com.helpnest.domain.faq.repository.FaqRepository;
import com.helpnest.domain.ticket.entity.TicketCategory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class FaqQueryAdapter implements FaqQueryPort {

    private final FaqRepository faqRepository;

    /** 조회수 높은 순. 알 수 없는 유형·limit 0 이하는 빈 목록 (AI 초안이 FAQ 없이도 진행되도록 예외 대신) */
    @Override
    @Transactional(readOnly = true)
    public List<FaqInfo> findPublishedByCategory(String category, String keyword, int limit) {
        TicketCategory parsed = parse(category);
        if (limit < 1 || (category != null && parsed == null)) {
            return List.of();
        }
        return faqRepository.search(true, parsed, FaqRepository.containsPattern(keyword),
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "viewCount")))
                .map(f -> new FaqInfo(f.getId(), f.getCategory().name(), f.getQuestion(), f.getAnswer()))
                .getContent();
    }

    private static TicketCategory parse(String category) {
        try {
            return category == null ? null : TicketCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
