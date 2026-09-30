// @owner BSJ
package com.helpnest.domain.faq.port;

import java.util.List;

/** 호출자: 신수진(AI 초안 컨텍스트) (docs/02 §5.2, docs/05) */
public interface FaqQueryPort {

    /** 같은 유형의 게시 FAQ 중 질문/답변에 keyword 가 포함된 것 최대 limit 건. keyword 가 null 이면 유형만으로 조회 */
    List<FaqInfo> findPublishedByCategory(String category, String keyword, int limit);
}
