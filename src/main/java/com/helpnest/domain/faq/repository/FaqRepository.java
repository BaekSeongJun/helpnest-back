// @owner BSJ
package com.helpnest.domain.faq.repository;

import com.helpnest.domain.faq.entity.Faq;
import com.helpnest.domain.ticket.entity.TicketCategory;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.util.StringUtils;

public interface FaqRepository extends JpaRepository<Faq, Long> {

    /**
     * published·category 는 null 이면 조건 없음. pattern 은 {@link #containsPattern} 으로 만든다.
     * ponytail: LIKE '%kw%' 전체 스캔 — FAQ 는 수십~수백 건 전제. 많아지면 pg_trgm/전문검색
     */
    @Query("""
            SELECT f FROM Faq f
            WHERE (:published IS NULL OR f.published = :published)
              AND (:category IS NULL OR f.category = :category)
              AND (LOWER(f.question) LIKE :pattern OR LOWER(f.answer) LIKE :pattern)
            """)
    Page<Faq> search(Boolean published, TicketCategory category, String pattern, Pageable pageable);

    /** 접수 폼 추천 후보 (FaqService#suggest) */
    List<Faq> findByPublishedTrue();

    /** 공개 글만 +1. 동시 조회에도 누락 없이 DB 에서 증가 (벌크 UPDATE 라 updated_at 은 안 바뀜) */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Faq f SET f.viewCount = f.viewCount + 1 WHERE f.id = :id AND f.published = TRUE")
    int incrementViewCount(Long id);

    /** keyword 가 비면 전체 일치 */
    static String containsPattern(String keyword) {
        return StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase() + "%" : "%";
    }
}
