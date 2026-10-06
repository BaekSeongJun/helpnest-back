// @owner BSJ
package com.helpnest.domain.customer.repository;

import com.helpnest.domain.survey.entity.Survey;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 고객 이력 묶음(CS-02 우측 패널·CS-07)의 읽기 전용 조회 (docs/02 §5 읽기 전용 예외 — "고객 이력(백성준)").
 * 참조 컬럼: {@code ticket(ticket_id, ticket_no, title, status, category, customer_id, guest_name, guest_email,
 * created_at)}, {@code member(member_id, name)}, {@code survey(ticket_id, rating, submitted_at)}.
 * 박민재의 {@code Ticket} 엔티티는 import 하지 않으려고 네이티브 쿼리를 쓴다. 쓰기는 하지 않는다.
 *
 * <p>고객 식별은 회원이면 {@code customerId}, 비회원이면 소문자 {@code email} 하나만 넘긴다(나머지는 null).
 * 이메일은 {@code lower(guest_email)} 함수 인덱스를 타도록 호출자가 소문자로 만들어 넘긴다.
 * 설문은 티켓당 1행(ticket_id UNIQUE)이라 LEFT JOIN 으로 행이 늘지 않는다.
 */
public interface CustomerHistoryRepository extends Repository<Survey, Long> {

    /** 고객의 문의 목록, 최근순. excludeTicketId 가 있으면 그 티켓(지금 보고 있는 티켓)은 뺀다 */
    @Query(value = """
            select t.ticket_id as "ticketId",
                   t.ticket_no as "ticketNo",
                   t.title as "title",
                   t.status as "status",
                   t.category as "category",
                   t.created_at as "createdAt",
                   s.rating as "rating"
            from ticket t
            left join survey s on s.ticket_id = t.ticket_id and s.submitted_at is not null
            where (cast(:customerId as bigint) is null or t.customer_id = :customerId)
              and (cast(:email as varchar) is null or lower(t.guest_email) = :email)
              and (cast(:excludeTicketId as bigint) is null or t.ticket_id <> :excludeTicketId)
            order by t.created_at desc, t.ticket_id desc
            """,
            countQuery = """
            select count(*)
            from ticket t
            where (cast(:customerId as bigint) is null or t.customer_id = :customerId)
              and (cast(:email as varchar) is null or lower(t.guest_email) = :email)
              and (cast(:excludeTicketId as bigint) is null or t.ticket_id <> :excludeTicketId)
            """,
            nativeQuery = true)
    Page<TicketRow> findTickets(@Param("customerId") Long customerId, @Param("email") String email,
            @Param("excludeTicketId") Long excludeTicketId, Pageable pageable);

    /** 고객 요약. 현재 티켓 포함 전체 기준이고, 평균은 제출된 별점만(없으면 null) */
    @Query(value = """
            select count(*) as "totalCount",
                   cast(avg(s.rating) as double precision) as "avgRating",
                   max(t.created_at) as "lastTicketAt",
                   coalesce(max(m.name), max(t.guest_name)) as "customerName"
            from ticket t
            left join member m on m.member_id = t.customer_id
            left join survey s on s.ticket_id = t.ticket_id and s.submitted_at is not null
            where (cast(:customerId as bigint) is null or t.customer_id = :customerId)
              and (cast(:email as varchar) is null or lower(t.guest_email) = :email)
            """, nativeQuery = true)
    SummaryRow summarize(@Param("customerId") Long customerId, @Param("email") String email);

    /** by-ticket 진입용: 티켓의 고객 식별자. 없는 티켓이면 null */
    @Query(value = """
            select t.customer_id as "customerId",
                   t.guest_email as "guestEmail"
            from ticket t
            where t.ticket_id = :ticketId
            """, nativeQuery = true)
    OwnerRow findOwner(@Param("ticketId") Long ticketId);

    interface TicketRow {
        Long getTicketId();

        String getTicketNo();

        String getTitle();

        String getStatus();

        String getCategory();

        /** 네이티브 프로젝션은 timestamptz 를 Instant 로 준다 */
        Instant getCreatedAt();

        /** 설문 미응답이면 null */
        Short getRating();
    }

    interface SummaryRow {
        Long getTotalCount();

        Double getAvgRating();

        Instant getLastTicketAt();

        String getCustomerName();
    }

    interface OwnerRow {
        Long getCustomerId();

        String getGuestEmail();
    }
}
