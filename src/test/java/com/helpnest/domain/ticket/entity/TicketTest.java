// @owner PMJ
package com.helpnest.domain.ticket.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Ticket 엔티티의 변경 메서드 단위 테스트(docs/10 §3.4 필수 항목 "배정").
 *
 * <p>전이 허용 여부는 검증하지 않는다. 엔티티는 판정하지 않고 상태만 바꾸며, 판정은
 * {@link com.helpnest.domain.ticket.service.TicketStateMachine} 과 그 테스트의 책임이다.
 * 여기서 확인하는 것은 "메서드를 부르면 어떤 필드가 함께 변하는가"뿐이다.
 */
@DisplayName("Ticket — 접수 기본값과 변경 메서드")
class TicketTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private static Ticket memberTicket() {
        return Ticket.builder()
                .ticketNo("HN-20261002-000123")
                .customerId(7L)
                .title("배송이 오지 않습니다")
                .content("3일 전 결제했는데 아직 배송 시작도 안 됐습니다.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(NOW.plusMinutes(1440))
                .build();
    }

    private static Ticket guestTicket() {
        return Ticket.builder()
                .ticketNo("HN-20261002-000124")
                .guestName("김손님")
                .guestEmail("guest@example.com")
                .guestPasswordHash("$2a$10$dummyhashvalueforunittest")
                .title("환불 문의")
                .content("주문을 취소하고 환불받고 싶습니다.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(NOW.plusMinutes(1440))
                .build();
    }

    @Nested
    @DisplayName("접수 직후 기본값")
    class Defaults {

        @Test
        @DisplayName("상태는 RECEIVED, 유형은 ETC, 우선순위는 NORMAL 로 시작한다")
        void 분류_전_기본값() {
            Ticket ticket = memberTicket();

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RECEIVED);
            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.ETC);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.NORMAL);
        }

        @Test
        @DisplayName("감정은 null 이다 — 중립이 아니라 아직 분류하지 않았다는 뜻이다")
        void 감정은_미분류() {
            assertThat(memberTicket().getSentiment()).isNull();
        }

        @Test
        @DisplayName("SLA 플래그와 처리 시각 컬럼은 비어 있다")
        void sla_플래그와_시각() {
            Ticket ticket = memberTicket();

            assertThat(ticket.isSlaWarned()).isFalse();
            assertThat(ticket.isSlaBreached()).isFalse();
            assertThat(ticket.getAgentId()).isNull();
            assertThat(ticket.getAssignedAt()).isNull();
            assertThat(ticket.getFirstRespondedAt()).isNull();
            assertThat(ticket.getResolvedAt()).isNull();
            assertThat(ticket.getClosedAt()).isNull();
        }

        @Test
        @DisplayName("customerId 유무로 회원·비회원 티켓을 구분한다")
        void 회원_비회원_구분() {
            assertThat(memberTicket().isMemberTicket()).isTrue();
            assertThat(guestTicket().isMemberTicket()).isFalse();
            assertThat(guestTicket().getGuestEmail()).isEqualTo("guest@example.com");
        }
    }

    @Nested
    @DisplayName("assignTo — 배정")
    class AssignTo {

        @Test
        @DisplayName("담당자와 배정 시각을 채우고 상태를 ASSIGNED 로 바꾼다")
        void 최초_배정() {
            Ticket ticket = memberTicket();

            ticket.assignTo(42L, NOW);

            assertThat(ticket.getAgentId()).isEqualTo(42L);
            assertThat(ticket.getAssignedAt()).isEqualTo(NOW);
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        }

        @Test
        @DisplayName("처리 중인 티켓을 재배정하면 담당자와 배정 시각이 갱신되고 상태가 ASSIGNED 로 돌아간다")
        void 재배정() {
            Ticket ticket = memberTicket();
            ticket.assignTo(42L, NOW);
            ticket.changeStatusTo(TicketStatus.IN_PROGRESS, NOW.plusMinutes(10));

            ticket.assignTo(99L, NOW.plusMinutes(30));

            assertThat(ticket.getAgentId()).isEqualTo(99L);
            assertThat(ticket.getAssignedAt()).isEqualTo(NOW.plusMinutes(30));
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        }
    }

    @Nested
    @DisplayName("changeStatusTo — 상태 변경")
    class ChangeStatusTo {

        @Test
        @DisplayName("RESOLVED 로 바꾸면 resolvedAt 만 채운다")
        void 해결_처리() {
            Ticket ticket = memberTicket();

            ticket.changeStatusTo(TicketStatus.RESOLVED, NOW);

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.RESOLVED);
            assertThat(ticket.getResolvedAt()).isEqualTo(NOW);
            assertThat(ticket.getClosedAt()).isNull();
        }

        @Test
        @DisplayName("CLOSED 로 바꾸면 closedAt 만 채운다")
        void 종료() {
            Ticket ticket = memberTicket();
            ticket.changeStatusTo(TicketStatus.RESOLVED, NOW);

            ticket.changeStatusTo(TicketStatus.CLOSED, NOW.plusHours(72));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CLOSED);
            assertThat(ticket.getResolvedAt()).isEqualTo(NOW);
            assertThat(ticket.getClosedAt()).isEqualTo(NOW.plusHours(72));
        }

        @Test
        @DisplayName("재문의로 RESOLVED 에서 IN_PROGRESS 로 돌아가도 resolvedAt 은 지우지 않는다")
        void 재문의() {
            Ticket ticket = memberTicket();
            ticket.changeStatusTo(TicketStatus.RESOLVED, NOW);

            ticket.changeStatusTo(TicketStatus.IN_PROGRESS, NOW.plusHours(1));

            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
            assertThat(ticket.getResolvedAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("AI 분류와 SLA 표시")
    class ClassificationAndSla {

        @Test
        @DisplayName("분류 결과가 유형·우선순위·감정에 반영된다")
        void 분류_반영() {
            Ticket ticket = memberTicket();

            ticket.applyClassification(TicketCategory.DELIVERY, TicketPriority.URGENT, Sentiment.NEGATIVE);

            assertThat(ticket.getCategory()).isEqualTo(TicketCategory.DELIVERY);
            assertThat(ticket.getPriority()).isEqualTo(TicketPriority.URGENT);
            assertThat(ticket.getSentiment()).isEqualTo(Sentiment.NEGATIVE);
        }

        @Test
        @DisplayName("분류만으로는 기한이 바뀌지 않는다 — 재계산은 호출자가 함께 해야 한다")
        void 분류는_기한을_건드리지_않는다() {
            Ticket ticket = memberTicket();
            OffsetDateTime originalDueAt = ticket.getFirstResponseDueAt();

            ticket.applyClassification(TicketCategory.DELIVERY, TicketPriority.URGENT, Sentiment.NEGATIVE);

            assertThat(ticket.getFirstResponseDueAt()).isEqualTo(originalDueAt);

            ticket.updateFirstResponseDueAt(NOW.plusMinutes(60));
            assertThat(ticket.getFirstResponseDueAt()).isEqualTo(NOW.plusMinutes(60));
        }

        @Test
        @DisplayName("첫 응답 시각은 한 번만 기록되고 이후 호출은 무시된다")
        void 첫_응답은_한_번만() {
            Ticket ticket = memberTicket();

            ticket.markFirstResponded(NOW.plusMinutes(5));
            ticket.markFirstResponded(NOW.plusMinutes(50));

            assertThat(ticket.getFirstRespondedAt()).isEqualTo(NOW.plusMinutes(5));
        }

        @Test
        @DisplayName("임박·위반 플래그는 켜지면 되돌릴 수 없다")
        void sla_플래그() {
            Ticket ticket = memberTicket();

            ticket.markSlaWarned();
            ticket.markSlaBreached();

            assertThat(ticket.isSlaWarned()).isTrue();
            assertThat(ticket.isSlaBreached()).isTrue();
        }
    }
}
