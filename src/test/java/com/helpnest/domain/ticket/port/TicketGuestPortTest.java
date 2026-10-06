// @owner PMJ
package com.helpnest.domain.ticket.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.repository.TicketRepository;

/**
 * 비회원 인증 포트 통합 테스트 (CR #34, PRD FR-AUTH-09).
 *
 * <p>호출자인 백성준의 {@code POST /api/auth/guest} 가 조회 두 메서드만으로 인증을 끝내므로,
 * 여기서 검증하는 것은 사실상 비회원 로그인의 성공·실패 조건 전체다. 세 번째 메서드
 * {@code updateGuestPassword} 는 비밀번호 재설정(CU-06)의 마지막 단계다.
 *
 * <p>비밀번호 비교는 호출자 책임이라 이 테스트도 반환된 해시를 {@code PasswordEncoder.matches}
 * 로 직접 확인한다 — 해시 문자열이 같은지 비교하면 BCrypt 가 매번 다른 salt 를 쓰므로 항상 틀린다.
 */
@SpringBootTest
@Transactional
@DisplayName("TicketGuestPort — 비회원 티켓 확인과 해시 조회·교체")
class TicketGuestPortTest {

    private static final String GUEST_EMAIL = "guest-login@example.com";
    private static final String RAW_PASSWORD = "guest-pw-1234";

    @Autowired
    TicketGuestPort guestPort;
    @Autowired
    TicketRepository ticketRepository;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    private Ticket givenGuestTicket() {
        return ticketRepository.save(baseBuilder()
                .guestName("김비회원")
                .guestEmail(GUEST_EMAIL)
                .guestPasswordHash(passwordEncoder.encode(RAW_PASSWORD))
                .build());
    }

    private Ticket givenMemberTicket() {
        return ticketRepository.save(baseBuilder()
                .customerId(memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId())
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

    @Nested
    @DisplayName("verifyGuest")
    class VerifyGuest {

        @Test
        @DisplayName("티켓번호와 이메일이 맞으면 ticketId 를 준다")
        void matches() {
            Ticket ticket = givenGuestTicket();

            assertThat(guestPort.verifyGuest(ticket.getTicketNo(), GUEST_EMAIL))
                    .isEqualTo(ticket.getId());
        }

        @Test
        @DisplayName("이메일 대소문자는 구분하지 않는다 — lower(guest_email) 부분 인덱스와 같은 기준")
        void ignoresEmailCase() {
            Ticket ticket = givenGuestTicket();

            assertThat(guestPort.verifyGuest(ticket.getTicketNo(), GUEST_EMAIL.toUpperCase()))
                    .isEqualTo(ticket.getId());
        }

        @Test
        @DisplayName("이메일이 다르면 null — 티켓번호만 알아내도 통과하면 안 된다")
        void rejectsWrongEmail() {
            Ticket ticket = givenGuestTicket();

            assertThat(guestPort.verifyGuest(ticket.getTicketNo(), "someone-else@example.com"))
                    .isNull();
        }

        @Test
        @DisplayName("회원 티켓은 대상이 아니다 — 조회 비밀번호가 없어 인증할 수단이 없다")
        void excludesMemberTicket() {
            Ticket ticket = givenMemberTicket();

            assertThat(guestPort.verifyGuest(ticket.getTicketNo(), GUEST_EMAIL)).isNull();
        }

        @Test
        @DisplayName("없는 티켓번호와 null 인자는 예외 없이 null — 존재 여부를 응답으로 알리지 않는다")
        void returnsNullInsteadOfThrowing() {
            assertThat(guestPort.verifyGuest("HN-20261002-999999", GUEST_EMAIL)).isNull();
            assertThat(guestPort.verifyGuest(null, GUEST_EMAIL)).isNull();
            assertThat(guestPort.verifyGuest("HN-20261002-999999", null)).isNull();
        }
    }

    @Nested
    @DisplayName("findGuestPasswordHash")
    class FindGuestPasswordHash {

        @Test
        @DisplayName("접수 때 저장한 BCrypt 해시를 그대로 준다 — 호출자가 matches 로 비교할 수 있다")
        void returnsHash() {
            Ticket ticket = givenGuestTicket();

            String hash = guestPort.findGuestPasswordHash(ticket.getId());

            assertThat(hash).isNotNull().startsWith("$2");
            assertThat(passwordEncoder.matches(RAW_PASSWORD, hash)).isTrue();
            assertThat(passwordEncoder.matches("wrong-password", hash)).isFalse();
        }

        @Test
        @DisplayName("회원 티켓이면 null")
        void nullForMemberTicket() {
            Ticket ticket = givenMemberTicket();

            assertThat(guestPort.findGuestPasswordHash(ticket.getId())).isNull();
        }

        @Test
        @DisplayName("없는 티켓과 null 인자는 예외 없이 null")
        void nullForUnknownTicket() {
            assertThat(guestPort.findGuestPasswordHash(-1L)).isNull();
            assertThat(guestPort.findGuestPasswordHash(null)).isNull();
        }
    }

    /**
     * 호출자는 백성준의 {@code PasswordResetService.resetGuestPassword} 다(FR-AUTH-09, CU-06).
     *
     * <p>교체 여부를 {@link TicketGuestPort#findGuestPasswordHash} 로 되읽어 확인한다. 엔티티
     * 필드를 직접 보면 영속성 컨텍스트 안의 값만 보게 되어 <b>플러시되지 않은 변경도 통과한다</b> —
     * 어댑터에 쓰기 트랜잭션을 빠뜨렸을 때 바로 이 함정에 빠진다. 포트로 되읽으면 JPQL select 가
     * 플러시를 유발해 실제로 SQL 까지 나갔는지 검증된다.
     */
    @Nested
    @DisplayName("updateGuestPassword")
    class UpdateGuestPassword {

        private static final String NEW_PASSWORD = "guest-new-pw-5678";

        @Test
        @DisplayName("비회원 티켓의 해시를 교체한다 — 새 비밀번호로 조회되고 옛 것은 막힌다")
        void replacesHash() {
            Ticket ticket = givenGuestTicket();

            guestPort.updateGuestPassword(ticket.getId(), passwordEncoder.encode(NEW_PASSWORD));

            String hash = guestPort.findGuestPasswordHash(ticket.getId());
            assertThat(passwordEncoder.matches(NEW_PASSWORD, hash)).isTrue();
            assertThat(passwordEncoder.matches(RAW_PASSWORD, hash)).isFalse();
        }

        @Test
        @DisplayName("회원 티켓은 바꾸지 않는다 — 아무도 쓰지 않는 자격증명이 조용히 생기면 안 된다")
        void ignoresMemberTicket() {
            Ticket ticket = givenMemberTicket();

            guestPort.updateGuestPassword(ticket.getId(), passwordEncoder.encode(NEW_PASSWORD));

            assertThat(guestPort.findGuestPasswordHash(ticket.getId())).isNull();
        }

        @Test
        @DisplayName("없는 티켓과 null 인자는 예외 없이 무시 — 존재 여부를 응답으로 알리지 않는다")
        void ignoresUnknownTicketAndNulls() {
            Ticket ticket = givenGuestTicket();
            String before = guestPort.findGuestPasswordHash(ticket.getId());

            assertThatCode(() -> {
                guestPort.updateGuestPassword(-1L, passwordEncoder.encode(NEW_PASSWORD));
                guestPort.updateGuestPassword(null, passwordEncoder.encode(NEW_PASSWORD));
                guestPort.updateGuestPassword(ticket.getId(), null);
            }).doesNotThrowAnyException();

            assertThat(guestPort.findGuestPasswordHash(ticket.getId())).isEqualTo(before);
        }
    }
}
