// @owner PMJ
package com.helpnest.domain.sla.repository;

import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.ticket.entity.TicketPriority;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * SLA 정책 조회. ID 타입이 Long 이 아니라 {@link TicketPriority} 인 점에 주의한다.
 *
 * <p>4행 고정 설정 테이블이므로 {@code findById(priority)} 만으로 충분하며,
 * S1 에서 조회 빈도가 문제가 되면 캐시를 서비스 계층에 두고 이 인터페이스는 그대로 둔다.
 */
public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, TicketPriority> {
}
