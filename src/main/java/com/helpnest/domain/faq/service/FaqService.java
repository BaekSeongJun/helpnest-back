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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FAQ 조회(공개)·관리(LEAD+) (FR-FAQ-01, 02, docs/04 §4) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FaqService {

    private static final int SUGGEST_LIMIT = 3;

    private final FaqRepository faqRepository;

    /** @param publishedOnly 고객 화면 true, 관리 화면 false(비공개 포함) */
    public PageResponse<FaqResponse> search(boolean publishedOnly, TicketCategory category, String keyword,
            Pageable pageable) {
        return PageResponse.from(faqRepository.search(publishedOnly ? Boolean.TRUE : null, category,
                FaqRepository.containsPattern(keyword), pageable).map(FaqResponse::from));
    }

    /**
     * 제목을 공백으로 나눈 단어(2자 이상) 중 질문·답변에 많이 들어 있는 글 순, 같으면 조회수 순으로 최대 3건.
     * 제목 전체를 한 덩어리로 찾으면 "환불 언제 되나요" 같은 문장은 어떤 글에도 일치하지 않는다.
     * 한 글자 단어는 거의 모든 글에 걸려 추천 의미가 없으므로 뺀다.
     * ponytail: 공개 글 전체를 메모리에서 채점 — FAQ 는 수십~수백 건 전제(search 와 같은 가정). 많아지면 pg_trgm/전문검색.
     * 조사가 붙은 단어("환불은")는 일치하지 않는다 — 형태소 분석이 필요해지면 그때 검색 엔진으로.
     */
    public List<FaqResponse> suggest(String q) {
        List<String> words = q == null ? List.of() : Arrays.stream(q.toLowerCase().split("\\s+"))
                .filter(w -> w.length() >= 2).distinct().toList();
        if (words.isEmpty()) {
            return List.of();
        }
        record Scored(Faq faq, long score) {
        }
        return faqRepository.findByPublishedTrue().stream()
                .map(f -> {
                    String text = (f.getQuestion() + " " + f.getAnswer()).toLowerCase();
                    return new Scored(f, words.stream().filter(text::contains).count());
                })
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingLong(Scored::score).reversed()
                        .thenComparing(s -> s.faq().getViewCount(), Comparator.reverseOrder()))
                .limit(SUGGEST_LIMIT)
                .map(s -> FaqResponse.from(s.faq()))
                .toList();
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
