// @owner BSJ
package com.helpnest.domain.faq.port;

import java.util.List;

/** 호출자: 신수진(AI 초안 컨텍스트, AI 상담 근거) (docs/02 §5.2, docs/05) */
public interface FaqQueryPort {

    /** 같은 유형의 게시 FAQ 중 질문/답변에 keyword 가 포함된 것 최대 limit 건. keyword 가 null 이면 유형만으로 조회 */
    List<FaqInfo> findPublishedByCategory(String category, String keyword, int limit);

    /**
     * 고객이 쓴 문장과 단어가 많이 겹치는 게시 FAQ 최대 limit 건(겹치는 단어 수 → 조회수 순). 유형을 모를 때 쓴다.
     * 겹치는 글이 없으면 빈 목록 — AI 상담은 이때 답을 지어내지 않고 상담원 연결로 넘긴다.
     */
    List<FaqInfo> findPublishedByQuestion(String question, int limit);
}
