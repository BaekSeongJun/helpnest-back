// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.ticket.dto.SlaFilter;
import com.helpnest.domain.ticket.dto.TicketSearchCondition;
import com.helpnest.domain.ticket.entity.Ticket;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * 콘솔 티켓 목록 검색 조건을 {@link Specification} 으로 조립한다 (docs/04 §7, 화면 CS-01).
 *
 * <h2>QueryDSL 을 쓰지 않은 이유</h2>
 * pom.xml 의존성 추가는 수정 금지 파일 변경(= CR 발생)이다. 남은 선택은 동적 JPQL 과
 * Criteria(Specification) 두 가지인데, SLA 필터가 <b>우선순위별로 다른 임박 시각</b>을 OR 로
 * 묶어야 해서 문자열 JPQL 로는 조건 수가 가변이 되어 표현할 수 없다. Specification 은
 * Spring Data JPA 에 이미 포함돼 있어 의존성이 늘지 않는다.
 *
 * <h2>이름(고객·담당자)을 여기서 JOIN 하지 않는 이유</h2>
 * member 는 백성준 소유라 Criteria 로 조인하려면 {@code Member} 엔티티를 import 해야 하고
 * (docs/10 §3.3 금지), 네이티브 SQL 로 바꾸면 이 동적 조건 조립을 문자열로 다시 써야 한다.
 * 그래서 이름은 {@code MemberNameLookupRepository} 로 <b>페이지당 한 번</b> 일괄 조회한다 —
 * 쿼리 수가 티켓 건수에 비례하지 않으므로 N+1 이 아니다(목록 1 + 카운트 1 + 이름 1 = 3).
 */
public final class TicketSpecs {

    private TicketSpecs() {
    }

    /**
     * 필터 7종을 AND 로 묶는다. 값이 null 인 필터는 조건에서 빠진다.
     *
     * @param policies SLA 필터용 정책 4행. SLA 조건이 없으면 쓰이지 않는다
     * @param now      임박·위반 판정 기준 시각. 인자로 받는 이유는 테스트에서 고정해야 하기 때문이다
     */
    public static Specification<Ticket> search(TicketSearchCondition c, List<SlaPolicy> policies,
            OffsetDateTime now) {
        return (root, query, cb) -> {
            List<Predicate> and = new ArrayList<>();

            if (c.status() != null) {
                and.add(cb.equal(root.get("status"), c.status()));
            }
            if (c.priority() != null) {
                and.add(cb.equal(root.get("priority"), c.priority()));
            }
            if (c.category() != null) {
                and.add(cb.equal(root.get("category"), c.category()));
            }
            if (c.isUnassignedOnly()) {
                and.add(cb.isNull(root.get("agentId")));
            } else if (c.agentId() != null) {
                and.add(cb.equal(root.get("agentId"), c.agentId()));
            }
            if (c.keyword() != null && !c.keyword().isBlank()) {
                and.add(keyword(root, cb, c.keyword()));
            }
            if (c.sla() != null) {
                and.add(sla(root, cb, c.sla(), policies, now));
            }
            return cb.and(and.toArray(Predicate[]::new));
        };
    }

    /**
     * 티켓번호·제목·본문 부분 일치(대소문자 무시).
     *
     * <p><b>한계</b>: {@code lower(column) like '%키워드%'} 는 앞쪽 와일드카드 때문에 인덱스를
     * 쓸 수 없어 전체 스캔이다. 티켓이 수십만 건이 되면 PostgreSQL 전문 검색(tsvector + GIN)
     * 이나 pg_trgm 인덱스로 바꿔야 한다. 포트폴리오 규모(수천 건)에서는 허용한다.
     */
    private static Predicate keyword(Root<Ticket> root, CriteriaBuilder cb, String keyword) {
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return cb.or(
                cb.like(cb.lower(root.get("ticketNo")), pattern),
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("content")), pattern));
    }

    /**
     * SLA 임박·위반 판정.
     *
     * <h2>임박을 createdAt 기준으로 역산하는 이유</h2>
     * 임박 시각은 {@code createdAt + warningRatio × responseMinutes} 인데 비율이 우선순위마다
     * 달라 JPQL·Criteria 의 날짜 연산으로는 표현할 수 없다. 그래서 부등식을 뒤집는다.
     * <pre>
     * now >= createdAt + warningMinutes   ⟺   createdAt <= now - warningMinutes
     * </pre>
     * 우변은 우선순위별 <b>상수 시각</b>이므로 Java 에서 계산해 넘기면 DB 는 단순 비교만 한다.
     * {@code created_at} 비교라 인덱스도 쓸 수 있다.
     *
     * <h2>위반에 sla_breached 를 함께 보는 이유</h2>
     * 기한을 넘긴 뒤 늦게라도 응답했으면 {@code firstRespondedAt} 이 채워져 시각 조건에서
     * 빠지지만, 위반 사실은 사라지지 않는다({@code Ticket.markSlaBreached} 주석 — 되돌리는
     * 메서드가 없다). 그래서 플래그가 켜진 티켓은 응답 여부와 무관하게 위반으로 센다.
     */
    private static Predicate sla(Root<Ticket> root, CriteriaBuilder cb, SlaFilter sla,
            List<SlaPolicy> policies, OffsetDateTime now) {
        Predicate notResponded = cb.isNull(root.get("firstRespondedAt"));

        if (sla == SlaFilter.BREACHED) {
            return cb.or(
                    cb.isTrue(root.get("slaBreached")),
                    cb.and(notResponded, cb.lessThan(root.get("firstResponseDueAt"), now)));
        }

        // WARNING: 미응답 + 기한 내 + 우선순위별 임박 시각 경과
        Predicate[] perPriority = policies.stream()
                .map(p -> cb.and(
                        cb.equal(root.get("priority"), p.getPriority()),
                        cb.lessThanOrEqualTo(root.get("createdAt"), warningCutoff(p, now))))
                .toArray(Predicate[]::new);

        return cb.and(notResponded,
                cb.greaterThanOrEqualTo(root.get("firstResponseDueAt"), now),
                cb.or(perPriority));
    }

    /**
     * 이 우선순위에서 "지금 임박"이 되는 접수 시각의 상한.
     *
     * <p>임박까지의 분을 {@link SlaPolicy#calculateWarningAt} 로부터 되뽑는다 — 비율 곱셈과
     * 반올림을 여기서 다시 쓰면 스케줄러(S2)의 판정과 어긋날 수 있고, 그러면 목록에 임박으로
     * 보이는데 알림은 오지 않는(또는 반대) 상태가 생긴다.
     */
    private static OffsetDateTime warningCutoff(SlaPolicy policy, OffsetDateTime now) {
        long warningMinutes = Duration.between(now, policy.calculateWarningAt(now)).toMinutes();
        return now.minusMinutes(warningMinutes);
    }
}
