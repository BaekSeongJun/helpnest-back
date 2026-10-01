// @owner BSJ
package com.helpnest.domain.template.repository;

import com.helpnest.domain.template.entity.Template;
import com.helpnest.domain.ticket.entity.TicketCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.util.StringUtils;

public interface TemplateRepository extends JpaRepository<Template, Long> {

    /**
     * active·category 는 null 이면 조건 없음. pattern 은 {@link #containsPattern} 으로 만든다.
     * ponytail: LIKE '%kw%' 전체 스캔 — 템플릿은 수십 건 전제
     */
    @Query("""
            SELECT t FROM Template t
            WHERE (:active IS NULL OR t.active = :active)
              AND (:category IS NULL OR t.category = :category)
              AND (LOWER(t.title) LIKE :pattern OR LOWER(t.content) LIKE :pattern)
            """)
    Page<Template> search(Boolean active, TicketCategory category, String pattern, Pageable pageable);

    /** keyword 가 비면 전체 일치 */
    static String containsPattern(String keyword) {
        return StringUtils.hasText(keyword) ? "%" + keyword.trim().toLowerCase() + "%" : "%";
    }
}
