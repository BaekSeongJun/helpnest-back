// @owner BSJ
package com.helpnest.domain.template.service;

import com.helpnest.domain.template.dto.TemplateRequest;
import com.helpnest.domain.template.dto.TemplateResponse;
import com.helpnest.domain.template.entity.Template;
import com.helpnest.domain.template.repository.TemplateRepository;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 답변 템플릿 조회(AGENT+)·관리(LEAD+) (FR-TPL-01, 02, docs/04 §4) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateService {

    private final TemplateRepository templateRepository;

    /** @param activeOnly 상담원 TemplatePicker true, 관리 화면 false(미사용 포함) */
    public PageResponse<TemplateResponse> search(boolean activeOnly, TicketCategory category, String keyword,
            Pageable pageable) {
        return PageResponse.from(templateRepository.search(activeOnly ? Boolean.TRUE : null, category,
                TemplateRepository.containsPattern(keyword), pageable).map(TemplateResponse::from));
    }

    @Transactional
    public TemplateResponse create(Long memberId, TemplateRequest req) {
        Template template = Template.builder()
                .category(req.category())
                .title(req.title())
                .content(req.content())
                .active(req.activeOrDefault())
                .createdBy(memberId)
                .build();
        return TemplateResponse.from(templateRepository.save(template));
    }

    @Transactional
    public TemplateResponse update(Long templateId, TemplateRequest req) {
        Template template = findOrThrow(templateId);
        template.update(req.category(), req.title(), req.content(), req.activeOrDefault());
        templateRepository.flush();   // 응답의 updatedAt 을 갱신된 값으로
        return TemplateResponse.from(template);
    }

    @Transactional
    public void delete(Long templateId) {
        templateRepository.delete(findOrThrow(templateId));
    }

    private Template findOrThrow(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }
}
