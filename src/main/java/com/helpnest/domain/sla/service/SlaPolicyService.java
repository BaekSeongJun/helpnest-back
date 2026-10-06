// @owner PMJ
package com.helpnest.domain.sla.service;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.sla.dto.SlaPolicyResponse;
import com.helpnest.domain.sla.dto.SlaPolicyUpdateRequest;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.entity.TicketPriority;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** SLA 정책 조회·수정 (docs/04 §8, PRD 6.1). 4행 고정 설정 테이블이라 추가·삭제는 없다 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SlaPolicyService {

    private final SlaPolicyRepository slaPolicyRepository;

    /**
     * 급한 순(URGENT → LOW)으로 내려준다. 관리 화면이 정렬을 또 구현하지 않게 하려는 것이고,
     * {@code TicketPriority} 선언 순서가 곧 급한 순이라 {@code ordinal} 로 충분하다.
     */
    public List<SlaPolicyResponse> findAll() {
        return slaPolicyRepository.findAll().stream()
                .sorted(Comparator.comparingInt(p -> p.getPriority().ordinal()))
                .map(SlaPolicyResponse::from)
                .toList();
    }

    /**
     * 정책을 고친다. 바뀐 값은 <b>이후 접수되는 티켓</b>부터 적용된다
     * ({@link SlaPolicy#update} 주석).
     *
     * <p>없는 우선순위면 {@link IllegalStateException} 이다 — 경로 변수가 enum 이라 여기까지
     * 온 값은 4종 중 하나이고, 그 행이 없다는 건 마이그레이션이 깨졌다는 뜻이라 사용자 입력
     * 오류(4xx)가 아니다({@code TicketService} 가 sla_policy 를 다루는 방식과 같다).
     */
    @Transactional
    public SlaPolicyResponse update(TicketPriority priority, SlaPolicyUpdateRequest req) {
        SlaPolicy policy = slaPolicyRepository.findById(priority)
                .orElseThrow(() -> new IllegalStateException(
                        "sla_policy 에 %s 정책이 없습니다. 마이그레이션 적용 상태를 확인해 주세요.".formatted(priority)));
        policy.update(req.responseMinutes(), req.warningRatio());
        log.info("[sla] 정책 변경 priority={} responseMinutes={} warningRatio={}",
                priority, req.responseMinutes(), req.warningRatio());
        return SlaPolicyResponse.from(policy);
    }
}
