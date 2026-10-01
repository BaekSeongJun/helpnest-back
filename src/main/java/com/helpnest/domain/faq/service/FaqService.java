// @owner BSJ
package com.helpnest.domain.faq.service;

import com.helpnest.domain.faq.dto.FaqRequest;
import com.helpnest.domain.faq.dto.FaqResponse;
import com.helpnest.domain.faq.entity.Faq;
import com.helpnest.domain.faq.repository.FaqRepository;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FAQ 조회(공개)·관리(LEAD+) (FR-FAQ-01, 02, docs/04 §4) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FaqService {

    private static final Pageable SUGGEST_PAGE = PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "viewCount"));

    private final FaqRepository faqRepository;

    /** @param publishedOnly 고객 화면 true, 관리 화면 false(비공개 포함) */
    public PageResponse<FaqResponse> search(boolean publishedOnly, TicketCategory category, String keyword,
            Pageable pageable) {
        return PageResponse.from(faqRepository.search(publishedOnly ? Boolean.TRUE : null, category,
                FaqRepository.containsPattern(keyword), pageable).map(FaqResponse::from));
    }

    /** 한 글자는 거의 모든 글에 걸려 추천 의미가 없으므로 2자부터 */
    public List<FaqResponse> suggest(String q) {
        if (q == null || q.trim().length() < 2) {
            return List.of();
        }
        return search(true, null, q, SUGGEST_PAGE).content();
    }

    /** 공개 글 상세 + 조회수 +1. 비공개·없는 글은 같은 404 (존재 여부 비노출) */
    @Transactional
    public FaqResponse getPublished(Long faqId) {
        if (faqRepository.incrementViewCount(faqId) == 0) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return FaqResponse.from(findOrThrow(faqId));
    }

    @Transactional
    public FaqResponse create(Long memberId, FaqRequest req) {
        Faq faq = Faq.builder()
                .category(req.category())
                .question(req.question())
                .answer(req.answer())
                .published(req.publishedOrDefault())
                .createdBy(memberId)
                .build();
        return FaqResponse.from(faqRepository.save(faq));
    }

    @Transactional
    public FaqResponse update(Long faqId, FaqRequest req) {
        Faq faq = findOrThrow(faqId);
        faq.update(req.category(), req.question(), req.answer(), req.publishedOrDefault());
        faqRepository.flush();   // 응답의 updatedAt 을 갱신된 값으로
        return FaqResponse.from(faq);
    }

    @Transactional
    public void delete(Long faqId) {
        faqRepository.delete(findOrThrow(faqId));
    }

    private Faq findOrThrow(Long faqId) {
        return faqRepository.findById(faqId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }
}
