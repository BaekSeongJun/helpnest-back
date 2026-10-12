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
import java.util.Set;
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
    /** 단어 끝에서 떼어 보는 어미·조사 — 긴 것을 먼저 본다("환불되나요" → "환불", "환불은" → "환불") */
    private static final List<String> SUFFIXES = List.of("해주세요", "되나요", "하나요", "인가요", "있나요", "없나요",
            "될까요", "할까요", "됩니다", "합니다", "했어요", "됐어요", "인데요", "해요", "돼요",
            "으로", "에서", "에게", "까지", "부터",
            "은", "는", "이", "가", "을", "를", "에", "의", "도", "로", "과", "와", "만");
    /** 어느 글에나 걸리는 물음말·서술어 — 채점에서 뺀다("언제 되나요" 가 "환불" 을 이기지 않게) */
    private static final Set<String> STOPWORDS = Set.of("언제", "어떻게", "어디", "어디서", "어디에서", "왜", "무엇", "뭐",
            "누가", "누구", "얼마", "얼마나", "어떤", "무슨", "되나요", "하나요", "인가요", "있나요", "없나요",
            "될까요", "할까요", "싶어요", "싶습니다", "해요", "돼요", "안돼요", "주세요", "해주세요", "알려주세요",
            "궁금해요", "궁금합니다", "있어요", "없어요");

    private final FaqRepository faqRepository;

    /** @param publishedOnly 고객 화면 true, 관리 화면 false(비공개 포함) */
    public PageResponse<FaqResponse> search(boolean publishedOnly, TicketCategory category, String keyword,
            Pageable pageable) {
        return PageResponse.from(faqRepository.search(publishedOnly ? Boolean.TRUE : null, category,
                FaqRepository.containsPattern(keyword), pageable).map(FaqResponse::from));
    }

    /**
     * 제목을 공백·문장부호로 나눈 단어(2자 이상) 중 질문·답변에 많이 들어 있는 글 순(질문에 있으면 2점, 답변에만 있으면 1점), 같으면 조회수 순으로 최대 3건.
     * 제목 전체를 한 덩어리로 찾으면 "환불 언제 되나요" 같은 문장은 어떤 글에도 일치하지 않는다.
     * 한 글자 단어와 물음말·서술어(STOPWORDS)는 거의 모든 글에 걸려 추천 의미가 없으므로 뺀다.
     * ponytail: 공개 글 전체를 메모리에서 채점 — FAQ 는 수십~수백 건 전제(search 와 같은 가정). 많아지면 pg_trgm/전문검색.
     * ponytail: 어미·조사는 목록(SUFFIXES)에 있는 것 하나만 끝에서 떼어 본다 — 목록에 없는 활용형은 못 찾는다.
     * 형태소 분석이 필요해지면 검색 엔진으로.
     */
    public List<FaqResponse> suggest(String q) {
        return rank(q, SUGGEST_LIMIT).stream().map(FaqResponse::from).toList();
    }

    /** 추천 채점 본체 — 접수 폼 추천과 AI 상담 근거 조회(FaqQueryPort)가 같이 쓴다 */
    public List<Faq> rank(String q, int limit) {
        List<String> words = q == null ? List.of() : Arrays.stream(q.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 2 && !STOPWORDS.contains(w)).distinct().toList();
        if (words.isEmpty()) {
            return List.of();
        }
        record Scored(Faq faq, long score) {
        }
        return faqRepository.findByPublishedTrue().stream()
                .map(f -> {
                    // 질문(제목)에 있는 단어는 2점, 답변에만 있으면 1점 — 답변에 스치듯 나온 글이 제목이 맞는 글을 이기지 않게
                    String question = f.getQuestion().toLowerCase();
                    String answer = f.getAnswer().toLowerCase();
                    return new Scored(f, words.stream()
                            .mapToLong(w -> has(question, w) ? 2 : has(answer, w) ? 1 : 0).sum());
                })
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingLong(Scored::score).reversed()
                        .thenComparing(s -> s.faq().getViewCount(), Comparator.reverseOrder()))
                .limit(Math.max(limit, 0))
                .map(Scored::faq)
                .toList();
    }

    private static boolean has(String text, String word) {
        return text.contains(word) || text.contains(stripSuffix(word));
    }

    /** 끝의 어미·조사 하나를 뗀다. 떼고 2자 미만이면 그대로("문의" 를 "문" 으로 만들지 않는다) */
    private static String stripSuffix(String word) {
        return SUFFIXES.stream()
                .filter(p -> word.endsWith(p) && word.length() - p.length() >= 2)
                .findFirst()
                .map(p -> word.substring(0, word.length() - p.length()))
                .orElse(word);
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
