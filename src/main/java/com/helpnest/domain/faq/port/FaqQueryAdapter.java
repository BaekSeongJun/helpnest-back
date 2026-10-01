// @owner BSJ
package com.helpnest.domain.faq.port;

import java.util.List;
import org.springframework.stereotype.Component;

// ponytail: S0 스텁 — FAQ 테이블(S1)이 생기면 실제 조회로 교체
@Component
public class FaqQueryAdapter implements FaqQueryPort {

    @Override
    public List<FaqInfo> findPublishedByCategory(String category, String keyword, int limit) {
        return List.of();
    }
}
