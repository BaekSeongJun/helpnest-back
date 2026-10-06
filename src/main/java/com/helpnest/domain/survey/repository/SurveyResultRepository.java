// @owner BSJ
package com.helpnest.domain.survey.repository;

import com.helpnest.domain.survey.entity.Survey;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 설문 결과 화면(CS-06)의 읽기 전용 조회. 티켓·회원의 이름·담당자·유형을 한 번에 JOIN 해야 해서
 * 포트 호출 N 번 대신 읽기 전용 네이티브 쿼리를 쓴다 (docs/02 §5 읽기 전용 예외 — "고객 이력·설문 결과(백성준)").
 * 같은 규정에 따라 참조 컬럼을 적는다: {@code ticket(ticket_id, ticket_no, customer_id, guest_name, agent_id, category)},
 * {@code member(member_id, name)}.
 *
 * <p>JPQL 이 아니라 네이티브인 이유는 박민재의 {@code Ticket} 엔티티를 import 하지 않기 위해서다(docs/10 §3.3).
 * 쓰기는 하지 않는다. {@code Repository} 마커로 Survey 의 CRUD 메서드가 딸려 오지 않게 한다.
 *
 * <h2>필터 규칙</h2>
 * 기간은 <b>발송일(sent_at)</b> 기준이다 — "이 기간에 보낸 설문의 결과". 제출일 기준이면 응답률의 분모(발송)와
 * 분자(응답)가 다른 모집단이 된다. 선택 조건은 {@code cast(:x as 타입) is null} 로 받는다 — null 바인딩의
 * 타입을 PostgreSQL 이 추론하지 못해 실패하기 때문이다.
 */
public interface SurveyResultRepository extends Repository<Survey, Long> {

    /** 제출된 응답만, 최근 제출 순. 별점 필터는 목록 전용 */
    @Query(value = """
            select t.ticket_id as "ticketId",
                   t.ticket_no as "ticketNo",
                   coalesce(c.name, t.guest_name) as "customerName",
                   a.name as "agentName",
                   s.rating as "rating",
                   s.comment as "comment",
                   s.submitted_at as "submittedAt"
            from survey s
            join ticket t on t.ticket_id = s.ticket_id
            left join member c on c.member_id = t.customer_id
            left join member a on a.member_id = t.agent_id
            where s.submitted_at is not null
              and (cast(:from as timestamptz) is null or s.sent_at >= :from)
              and (cast(:to as timestamptz) is null or s.sent_at < :to)
              and (cast(:rating as smallint) is null or s.rating = :rating)
              and (cast(:agentId as bigint) is null or t.agent_id = :agentId)
              and (cast(:category as varchar) is null or t.category = :category)
            order by s.submitted_at desc, s.survey_id desc
            """,
            countQuery = """
            select count(*)
            from survey s
            join ticket t on t.ticket_id = s.ticket_id
            where s.submitted_at is not null
              and (cast(:from as timestamptz) is null or s.sent_at >= :from)
              and (cast(:to as timestamptz) is null or s.sent_at < :to)
              and (cast(:rating as smallint) is null or s.rating = :rating)
              and (cast(:agentId as bigint) is null or t.agent_id = :agentId)
              and (cast(:category as varchar) is null or t.category = :category)
            """,
            nativeQuery = true)
    Page<ResultRow> findResults(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("rating") Short rating, @Param("agentId") Long agentId, @Param("category") String category,
            Pageable pageable);

    /**
     * 발송·응답 수와 별점 분포. 별점 필터는 받지 않는다 — 분포가 곧 별점 축이라 걸러 버리면 응답률이 왜곡된다.
     * 응답이 없으면 avg 는 null, 분포는 0.
     */
    @Query(value = """
            select count(*) as "sent",
                   count(s.submitted_at) as "responded",
                   cast(avg(s.rating) as double precision) as "avgRating",
                   count(*) filter (where s.rating = 1) as "r1",
                   count(*) filter (where s.rating = 2) as "r2",
                   count(*) filter (where s.rating = 3) as "r3",
                   count(*) filter (where s.rating = 4) as "r4",
                   count(*) filter (where s.rating = 5) as "r5"
            from survey s
            join ticket t on t.ticket_id = s.ticket_id
            where (cast(:from as timestamptz) is null or s.sent_at >= :from)
              and (cast(:to as timestamptz) is null or s.sent_at < :to)
              and (cast(:agentId as bigint) is null or t.agent_id = :agentId)
              and (cast(:category as varchar) is null or t.category = :category)
            """, nativeQuery = true)
    SummaryRow summarize(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("agentId") Long agentId, @Param("category") String category);

    interface ResultRow {
        Long getTicketId();

        String getTicketNo();

        String getCustomerName();

        String getAgentName();

        Short getRating();

        String getComment();

        /** 네이티브 프로젝션은 timestamptz 를 Instant 로 준다 (OffsetDateTime 으로는 변환되지 않는다) */
        Instant getSubmittedAt();
    }

    interface SummaryRow {
        Long getSent();

        Long getResponded();

        Double getAvgRating();

        Long getR1();

        Long getR2();

        Long getR3();

        Long getR4();

        Long getR5();
    }
}
