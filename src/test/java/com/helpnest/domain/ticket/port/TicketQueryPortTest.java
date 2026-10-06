// @owner PMJ
package com.helpnest.domain.ticket.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;

/**
 * 해결 메일 정보 조회 포트 통합 테스트 (CR #48).
 *
 * <p>호출자인 백성준의 {@code SurveyListener} 가 이 메서드 하나로 메일 수신자와 본문 재료를
 * 모두 얻으므로, 여기서 검증하는 것은 사실상 결과 메일이 올바른 사람에게 올바른 내용으로
 * 가는지다.
 *
 * <p><b>가장 중요한 케이스는 {@code excludesInternalNote} 다.</b> 내부 메모가
 * {@code lastPublicReply} 로 새면 상담원끼리 주고받은 메모가 그대로 고객 메일로 발송된다.
 * 조건이 Repository 메서드 이름에 박혀 있어 실수로 빠뜨릴 수는 없지만, 쿼리를 나중에 고칠 때
 * 깨지는 것을 이 테스트가 잡는다.
 *
 * <p>{@code @Transactional} 로 매 테스트가 롤백된다 — ticket 행을 남기면 티켓번호 생성·목록
 * 테스트가 함께 깨진다({@code TicketGuestPortTest} 와 같은 이유).
 */
@SpringBootTest
@Transactional
@DisplayName("TicketQueryPort — 해결 메일 정보 조회")
class TicketQueryPortTest {

    private static final String GUEST_NAME = "김비회원";
    private static final String GUEST_EMAIL = "guest-mail@example.com";

    @Autowired
    TicketQueryPort queryPort;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    TicketReplyRepository ticketReplyRepository;
    @Autowired
    MemberRepository memberRepository;

    private Ticket givenGuestTicket() {
        return ticketRepository.save(baseBuilder()
                .guestName(GUEST_NAME)
                .guestEmail(GUEST_EMAIL)
                .build());
    }

    private Ticket givenMemberTicket() {
        return ticketRepository.save(baseBuilder()
                .customerId(memberId("customer1@helpnest.local"))
                .build());
    }

    private Ticket.TicketBuilder baseBuilder() {
        return Ticket.builder()
                .ticketNo("HN-20261002-%06d".formatted(ticketRepository.nextTicketNoSeq()))
                .title("주문한 상품이 아직 안 왔어요")
                .content("배송 조회가 안 됩니다.")
                .channel(TicketChannel.WEB)
                .firstResponseDueAt(OffsetDateTime.now().plusDays(1));
    }

    private Long memberId(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getId();
    }

    /** 답변을 직접 저장한다 — 답변 API 를 거치면 상태 전이·첫 응답 기록까지 섞여 조회 검증이 흐려진다 */
    private TicketReply givenReply(Ticket ticket, WriterType writerType, boolean internal,
            String content) {
        return ticketReplyRepository.save(TicketReply.builder()
                .ticketId(ticket.getId())
                .writerId(writerType == WriterType.AGENT ? memberId("agent1@helpnest.local")
                        : ticket.getCustomerId())
                .writerType(writerType)
                .content(content)
                .isInternal(internal)
                .build());
    }

    @Nested
    @DisplayName("수신자 정보")
    class Recipient {

        @Test
        @DisplayName("회원 티켓은 customerId 만 주고 이름·이메일은 비운다 — 호출자가 MemberQueryPort 로 채운다")
        void memberTicketGivesIdOnly() {
            Ticket ticket = givenMemberTicket();

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.customerId()).isEqualTo(ticket.getCustomerId());
            assertThat(info.guestName()).isNull();
            assertThat(info.guestEmail()).isNull();
            assertThat(info.ticketNo()).isEqualTo(ticket.getTicketNo());
            assertThat(info.title()).isEqualTo(ticket.getTitle());
        }

        @Test
        @DisplayName("비회원 티켓은 이름·이메일을 직접 준다 — member 행이 없어 티켓이 유일한 출처다")
        void guestTicketGivesNameAndEmail() {
            Ticket ticket = givenGuestTicket();

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.customerId()).isNull();
            assertThat(info.guestName()).isEqualTo(GUEST_NAME);
            assertThat(info.guestEmail()).isEqualTo(GUEST_EMAIL);
        }
    }

    @Nested
    @DisplayName("lastPublicReply")
    class LastPublicReply {

        /**
         * 두 답변이 한 트랜잭션에서 저장되어 {@code created_at} 이 같은 값을 받을 수 있다
         * ({@code @CreationTimestamp} + 시계 해상도). 그래서 이 테스트는 정렬 동률까지 함께
         * 검증한다 — Repository 쿼리의 {@code IdDesc} 타이브레이커가 빠지면 여기서 깨진다.
         */
        @Test
        @DisplayName("가장 최근 상담원 공개 답변을 원문 그대로 준다 — created_at 동률이어도 뒤에 쓴 것")
        void returnsLatestPublicAgentReply() {
            Ticket ticket = givenGuestTicket();
            givenReply(ticket, WriterType.AGENT, false, "첫 번째 답변입니다.");
            givenReply(ticket, WriterType.AGENT, false, "추가 안내드립니다. 배송이 재개되었습니다.");

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.lastPublicReply()).isEqualTo("추가 안내드립니다. 배송이 재개되었습니다.");
        }

        @Test
        @DisplayName("내부 메모는 절대 포함하지 않는다 — 새면 상담원 메모가 고객 메일로 발송된다")
        void excludesInternalNote() {
            Ticket ticket = givenGuestTicket();
            givenReply(ticket, WriterType.AGENT, false, "확인 후 안내드리겠습니다.");
            givenReply(ticket, WriterType.AGENT, true, "이 고객 지난번에도 컴플레인. 주의 필요");

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.lastPublicReply()).isEqualTo("확인 후 안내드리겠습니다.");
        }

        @Test
        @DisplayName("고객 답글은 상담원 답변이 아니다 — 고객이 쓴 글이 답변으로 되돌아가면 안 된다")
        void excludesCustomerReply() {
            Ticket ticket = givenMemberTicket();
            givenReply(ticket, WriterType.AGENT, false, "처리해 드렸습니다.");
            givenReply(ticket, WriterType.CUSTOMER, false, "아직 해결되지 않았어요.");

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.lastPublicReply()).isEqualTo("처리해 드렸습니다.");
        }

        @Test
        @DisplayName("공개 답변이 없으면 null — 답변 없이 해결된 티켓도 결과 메일은 가야 한다")
        void nullWhenNoPublicReply() {
            Ticket ticket = givenGuestTicket();
            givenReply(ticket, WriterType.AGENT, true, "내부 처리만 하고 종료");

            ResolvedMailInfo info = queryPort.getResolvedMailInfo(ticket.getId());

            assertThat(info.lastPublicReply()).isNull();
            assertThat(info.ticketNo()).isEqualTo(ticket.getTicketNo());
        }
    }

    @Test
    @DisplayName("없는 티켓은 예외 — 조용히 null 을 주면 해결 통보가 영구히 누락된다")
    void throwsForUnknownTicket() {
        assertThatThrownBy(() -> queryPort.getResolvedMailInfo(-1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                        .isEqualTo(TicketErrorCode.NOT_FOUND.code()));
    }
}
