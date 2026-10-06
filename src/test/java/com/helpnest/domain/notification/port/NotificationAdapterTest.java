// @owner PMJ
package com.helpnest.domain.notification.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.assignment.service.AssignmentService;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.notification.entity.Notification;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.repository.NotificationRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 알림 저장 계층 통합 테스트 (docs/03 §3.2, docs/04 §9).
 *
 * <p>mock 이 아니라 실제 PostgreSQL 을 쓰는 이유: 검증 대상의 절반이 DDL 과의 합의다.
 * 300자 절단은 VARCHAR(300) 을 넘기지 않으려는 것이고 type 검증은 VARCHAR(30) 컬럼에
 * enum 이름이 들어가는 것을 전제한다. 어댑터를 mock repository 로 검증하면 컬럼 길이가
 * 바뀌어도 테스트는 계속 통과한다.
 *
 * <p><b>mock 빈을 쓰지 않는다.</b> {@code @MockitoBean} 은 컨텍스트 키를 바꿔 스프링 컨텍스트가
 * 하나 더 캐시되고, 컨텍스트마다 Hikari 풀을 들고 있어 PostgreSQL {@code max_connections} 를
 * 넘긴다 — 그러면 남의 테스트가 커넥션을 못 얻어 깨진다. "가용 상담원이 없는" 상황은 테스트
 * 트랜잭션 안에서 시드 상담원의 available 을 내려 만들고, 롤백으로 원복된다.
 */
@SpringBootTest
@Transactional
@DisplayName("알림 저장 계층 — 어댑터 검증·절단과 조회 쿼리")
class NotificationAdapterTest {

    @Autowired
    NotificationPort notificationPort;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    AssignmentService assignmentService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private Long receiverId() {
        return memberRepository.findByEmail("lead@helpnest.local").orElseThrow().getId();
    }

    @Nested
    @DisplayName("notify")
    class Notify {

        @Test
        @DisplayName("정상 호출은 type 을 enum 으로 변환해 저장하고 처음엔 미읽음이다")
        void savesAsUnread() {
            notificationPort.notify(receiverId(), "UNASSIGNED", null, "미배정 티켓이 있습니다.");

            assertThat(notificationRepository.findAll()).singleElement().satisfies(noti -> {
                assertThat(noti.getReceiverId()).isEqualTo(receiverId());
                assertThat(noti.getType()).isEqualTo(NotificationType.UNASSIGNED);
                assertThat(noti.getTicketId()).isNull();
                assertThat(noti.isRead()).isFalse();
                assertThat(noti.getCreatedAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("301자 문구는 300자로 잘라 저장한다 — message 가 VARCHAR(300) 이다")
        void truncatesOverLongMessage() {
            String tooLong = "가".repeat(Notification.MESSAGE_MAX_LENGTH + 1);

            notificationPort.notify(receiverId(), "SLA_WARNING", null, tooLong);

            assertThat(notificationRepository.findAll()).singleElement()
                    .extracting(Notification::getMessage, org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .hasSize(Notification.MESSAGE_MAX_LENGTH)
                    .isEqualTo(tooLong.substring(0, Notification.MESSAGE_MAX_LENGTH));
        }

        @Test
        @DisplayName("정확히 300자는 그대로 저장한다 — 경계에서 한 글자를 깎지 않는다")
        void keepsExactMaxLength() {
            String exact = "나".repeat(Notification.MESSAGE_MAX_LENGTH);

            notificationPort.notify(receiverId(), "SLA_WARNING", null, exact);

            assertThat(notificationRepository.findAll()).singleElement()
                    .extracting(Notification::getMessage).isEqualTo(exact);
        }

        @Test
        @DisplayName("8종에 없는 type 은 저장하지 않고 예외도 올리지 않는다 — 호출자 업무가 멈추면 안 된다")
        void rejectsUnknownTypeWithoutThrowing() {
            Long receiver = receiverId();

            assertThatCode(() -> {
                notificationPort.notify(receiver, "SOMETHING_ELSE", null, "문구");
                notificationPort.notify(receiver, "unassigned", null, "소문자도 다른 값이다");
                notificationPort.notify(receiver, null, null, "문구");
            }).doesNotThrowAnyException();

            assertThat(notificationRepository.count()).isZero();
        }

        @Test
        @DisplayName("수신자가 없거나 문구가 비면 저장하지 않는다 — 비회원은 웹 알림 대상이 아니다")
        void rejectsMissingReceiverOrBlankMessage() {
            Long receiver = receiverId();

            assertThatCode(() -> {
                notificationPort.notify(null, "UNASSIGNED", null, "문구");
                notificationPort.notify(receiver, "UNASSIGNED", null, null);
                notificationPort.notify(receiver, "UNASSIGNED", null, "   ");
            }).doesNotThrowAnyException();

            assertThat(notificationRepository.count()).isZero();
        }
    }

    /**
     * 스텁이던 구간에 쌓인 호출이 실제로 되살아났는지 확인한다. 어댑터 단독 테스트로는
     * {@code AssignmentService} 가 넘기는 type 문자열이 enum 8종과 어긋났을 때를 잡지 못한다 —
     * 그 상수는 private 이라 테스트가 직접 참조할 수도 없어 경로째로 돌려 본다.
     */
    @Nested
    @DisplayName("기존 호출처 소급 동작")
    class ExistingCallers {

        @Test
        @DisplayName("가용 상담원이 없는 자동 배정은 LEAD·ADMIN 에게 UNASSIGNED 행을 남긴다")
        void unassignedPathPersistsRows() {
            // MemberQueryAdapter.findAssignableAgents 는 role=AGENT·status=ACTIVE·available=true 를
            // 찾는다. 테스트 트랜잭션 안의 UPDATE 라 롤백되며 시드는 그대로 남는다
            jdbcTemplate.update("update member set available = false where role = 'AGENT'");
            Ticket ticket = ticketRepository.save(Ticket.builder()
                    .ticketNo("HN-20261006-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                    // chk_ticket_customer: 회원·비회원 중 하나는 반드시 있어야 한다
                    .customerId(memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId())
                    .title("배송이 오지 않습니다")
                    .content("주문한 지 일주일이 지났습니다.")
                    .channel(TicketChannel.WEB)
                    .firstResponseDueAt(OffsetDateTime.now().plusDays(1))
                    .build());

            Long assigned = assignmentService.autoAssign(ticket.getId());

            assertThat(assigned).isNull();
            assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStatus())
                    .isEqualTo(TicketStatus.RECEIVED);
            assertThat(notificationRepository.findAll())
                    .isNotEmpty()
                    .allSatisfy(noti -> {
                        assertThat(noti.getType()).isEqualTo(NotificationType.UNASSIGNED);
                        assertThat(noti.getTicketId()).isEqualTo(ticket.getId());
                        assertThat(noti.getMessage()).contains(ticket.getTicketNo());
                    })
                    .extracting(Notification::getReceiverId)
                    .containsExactlyInAnyOrderElementsOf(leadAndAdminIds());
        }

        /** 시드의 활성 LEAD·ADMIN (R__seed_BSJ_member.sql) */
        private List<Long> leadAndAdminIds() {
            return List.of("lead@helpnest.local", "admin@helpnest.local").stream()
                    .map(email -> memberRepository.findByEmail(email).orElseThrow().getId())
                    .toList();
        }
    }

    /**
     * 조회 세 개는 알림 벨 API(docs/04 §9) 전체의 입력이다. unreadOnly 분기는 쿼리 안의
     * {@code (:unreadOnly = false or ...)} 로 구현돼 있어 두 값을 모두 넣어봐야 의미가 있다.
     */
    @Nested
    @DisplayName("조회 쿼리")
    class Queries {

        @Test
        @DisplayName("unreadOnly 분기·미읽음 수·모두 읽음")
        void readFiltersAndBulkRead() {
            Long receiver = receiverId();
            Long other = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
            notificationPort.notify(receiver, "ASSIGNED", null, "첫 번째");
            notificationPort.notify(receiver, "CUSTOMER_REPLY", null, "두 번째");
            notificationPort.notify(other, "ASSIGNED", null, "남의 알림");
            // 최신 1건만 읽음으로 바꿔 unreadOnly 분기가 실제로 걸러지는지 본다
            notificationRepository.findForReceiver(receiver, false, PageRequest.of(0, 1))
                    .forEach(Notification::markRead);
            notificationRepository.flush();

            assertThat(notificationRepository.findForReceiver(receiver, false, PageRequest.of(0, 10)))
                    .extracting(Notification::getMessage)
                    .containsExactly("두 번째", "첫 번째");   // id 내림차순 = 최신 먼저
            assertThat(notificationRepository.findForReceiver(receiver, true, PageRequest.of(0, 10)))
                    .extracting(Notification::getMessage)
                    .containsExactly("첫 번째");
            assertThat(notificationRepository.countUnread(receiver)).isEqualTo(1);

            assertThat(notificationRepository.markAllRead(receiver)).isEqualTo(1);
            assertThat(notificationRepository.countUnread(receiver)).isZero();
            // 남의 알림은 건드리지 않는다
            assertThat(notificationRepository.countUnread(other)).isEqualTo(1);
        }
    }
}
