// @owner PMJ
package com.helpnest.domain.ticket.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;

import jakarta.persistence.LockModeType;

/**
 * 티켓 조회·저장.
 *
 * <p>목록 검색·SLA 감시 대상 조회 같은 커스텀 쿼리는 화면 명세(docs/09)와 API 명세(docs/04)가
 * 요구하는 정렬·필터를 구현하는 S1 후속 태스크에서 추가한다. 통계·목록용 읽기 전용 JPQL 은
 * 이 패키지에서 허용되지만 다른 도메인 테이블에 대한 쓰기는 반드시 포트를 거쳐야 한다
 * (docs/02 §5).
 */
/**
 * 콘솔 목록 검색은 {@link JpaSpecificationExecutor#findAll(org.springframework.data.jpa.domain.Specification,
 * org.springframework.data.domain.Pageable)} 에 {@code TicketSpecs.search} 를 넘겨 쓴다 —
 * 필터 7종이 동적이라 고정 JPQL 로는 표현할 수 없다({@code TicketSpecs} 주석).
 */
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    /**
     * 티켓번호 일련번호를 발급한다. 시퀀스는 V202609301110__PMJ_create_ticket.sql 에서 생성한다.
     *
     * <p>엔티티 식별자가 아니라 사람이 읽는 번호를 만드는 값이므로 {@code @GeneratedValue} 가
     * 아니라 명시적으로 당겨 쓴다. 시퀀스 증가는 트랜잭션 롤백에 영향받지 않는다 —
     * 번호 중복을 막는 것이 목적이므로 이것이 의도된 동작이다.
     */
    @Query(value = "SELECT nextval('ticket_no_seq')", nativeQuery = true)
    long nextTicketNoSeq();

    /**
     * 배정·분류 반영용 비관적 락 조회 (SELECT ... FOR UPDATE).
     *
     * <p>자동 배정은 "읽고 → 판단하고 → 쓰는" 흐름이라 같은 티켓에 두 번 돌면 나중 것이 앞의
     * 배정을 덮어쓴다. AI 분류 리스너는 비동기이고 팀장의 수동 배정은 사람이 아무 때나 누르므로
     * 두 흐름이 겹칠 수 있다. 티켓 1행을 잠가 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /**
     * 상담원별 처리 중 티켓 수 (PRD 6.2 최소 부하 배정의 입력).
     *
     * <p>한 건도 없는 상담원은 결과에 나오지 않는다(group by 특성) — 호출자가 0 으로 채워야 한다.
     * 결과를 0 으로 채우는 쪽이 쿼리에 outer join 을 넣는 것보다 단순하다.
     */
    @Query("""
            select new com.helpnest.domain.ticket.repository.AgentLoad(t.agentId, count(t))
            from Ticket t
            where t.agentId in :agentIds and t.status in (
                com.helpnest.domain.ticket.entity.TicketStatus.ASSIGNED,
                com.helpnest.domain.ticket.entity.TicketStatus.IN_PROGRESS)
            group by t.agentId
            """)
    List<AgentLoad> countActiveByAgentIds(@Param("agentIds") List<Long> agentIds);

    /**
     * 티켓번호 + 비회원 이메일로 비회원 티켓을 찾는다 ({@code TicketGuestPort.verifyGuest}).
     *
     * <p>{@code lower()} 로 비교하는 이유는 DDL 의 {@code idx_ticket_guest_email} 이
     * {@code lower(guest_email)} 부분 인덱스이기 때문이다. 한쪽만 lower() 를 쓰면 인덱스를 타지 않는다.
     *
     * <p>{@code customerId is null} 로 회원 티켓을 제외한다 — 회원 티켓에는 조회 비밀번호가 없어
     * 비회원 인증 대상이 아니다.
     */
    @Query("""
            select t.id from Ticket t
            where t.ticketNo = :ticketNo
              and lower(t.guestEmail) = lower(:email)
              and t.customerId is null
            """)
    Optional<Long> findGuestTicketId(@Param("ticketNo") String ticketNo, @Param("email") String email);

    /**
     * 비회원 티켓의 조회 비밀번호 BCrypt 해시 ({@code TicketGuestPort.findGuestPasswordHash}).
     * 회원 티켓이면 결과가 없다.
     */
    @Query("select t.guestPasswordHash from Ticket t where t.id = :id and t.customerId is null")
    Optional<String> findGuestPasswordHash(@Param("id") Long id);

    /**
     * 회원 고객의 문의 목록 (GET /api/tickets/my, 화면 CU-08).
     * 정렬은 호출자가 {@code Pageable} 로 넘긴다 — 기본값은 컨트롤러의 {@code @PageableDefault} 다.
     *
     * <p>비회원 티켓은 customer_id 가 NULL 이라 이 조회에 걸리지 않는다. 비회원은 목록 대신
     * Guest 토큰으로 자기 티켓 1건만 본다.
     */
    Page<Ticket> findByCustomerId(Long customerId, Pageable pageable);

    /**
     * 첫 응답 기한을 넘긴 미응답 티켓 ({@code SlaScheduler}).
     *
     * <p>위반은 우선순위와 무관하게 {@code first_response_due_at} 한 컴럼만 보면 된다 —
     * 기한 자체가 이미 정책을 반영해 계산된 값이다. 임박과 달리 정책별로 나눌 필요가 없다.
     *
     * <p>{@code slaBreached = false} 로 이미 알린 건을 제외한다. 플래그는 되돌리지 않으므로
     * ({@code Ticket.markSlaBreached}) 티켓당 알림은 정확히 한 번이다.
     */
    @Query("""
            select t from Ticket t
            where t.slaBreached = false
              and t.firstRespondedAt is null
              and t.firstResponseDueAt < :now
              and t.status not in :doneStatuses
            """)
    List<Ticket> findSlaBreachTargets(@Param("now") OffsetDateTime now,
            @Param("doneStatuses") Collection<TicketStatus> doneStatuses);

    /**
     * 임박 시각을 지났지만 아직 기한은 남은 미응답 티켓 ({@code SlaScheduler}).
     *
     * <p>임박 시각은 우선순위마다 다르므로 호출자가 정책 하나씩 넘긴다. 전달받는
     * {@code warningCutoff} 는 {@code TicketSpecs.warningCutoff} 가 계산한 단일 기준이다 —
     * 콘솔 목록의 임박 배지와 같은 식을 써야 "목록은 임박인데 알림은 안 오는" 상태가 없다.
     *
     * <p>{@code firstResponseDueAt >= now} 로 이미 기한을 넘긴 건을 빼는다. 위반 조회가 가져가므로
     * 한 티켓이 같은 회차에 임박과 위반을 두 번 받지 않는다.
     */
    @Query("""
            select t from Ticket t
            where t.slaWarned = false
              and t.firstRespondedAt is null
              and t.priority = :priority
              and t.createdAt <= :warningCutoff
              and t.firstResponseDueAt >= :now
              and t.status not in :doneStatuses
            """)
    List<Ticket> findSlaWarningTargets(@Param("priority") TicketPriority priority,
            @Param("warningCutoff") OffsetDateTime warningCutoff,
            @Param("now") OffsetDateTime now,
            @Param("doneStatuses") Collection<TicketStatus> doneStatuses);

    /**
     * 해결된 뒤 응답 없이 방치된 자동 종료 대상의 ticket_id
     * (docs/03 §4.9, {@code AutoCloseScheduler}).
     *
     * <p><b>엔티티가 아니라 id 만 반환한다.</b> 전이는 {@code TicketService.changeStatus} 가
     * {@code findByIdForUpdate} 로 다시 읽어 수행하므로(비관적 락으로 동시 전이를 막는다)
     * 여기서 엔티티까지 끌고 오면 그대로 버려진다.
     *
     * <p>설문 제출로 이미 종료된 티켓은 상태 조건에서 빠지고, 재문의로 IN_PROGRESS 가 된
     * 티켓도 같다 — {@code resolved_at} 은 남아 있지만 상태가 RESOLVED 가 아니다. 즉
     * "72시간 안에 아무 일도 일어나지 않은 티켓"만 남는다.
     */
    @Query("""
            select t.id from Ticket t
            where t.status = com.helpnest.domain.ticket.entity.TicketStatus.RESOLVED
              and t.resolvedAt < :cutoff
            """)
    List<Long> findAutoCloseTargets(@Param("cutoff") OffsetDateTime cutoff);
}
