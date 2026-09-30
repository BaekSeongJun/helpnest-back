// @owner PMJ
package com.helpnest.domain.ticket.service;

import static com.helpnest.domain.ticket.entity.ActorRole.ADMIN;
import static com.helpnest.domain.ticket.entity.ActorRole.AGENT;
import static com.helpnest.domain.ticket.entity.ActorRole.CUSTOMER;
import static com.helpnest.domain.ticket.entity.ActorRole.LEAD;
import static com.helpnest.domain.ticket.entity.ActorRole.SYSTEM;
import static com.helpnest.domain.ticket.entity.TicketStatus.ASSIGNED;
import static com.helpnest.domain.ticket.entity.TicketStatus.CLOSED;
import static com.helpnest.domain.ticket.entity.TicketStatus.IN_PROGRESS;
import static com.helpnest.domain.ticket.entity.TicketStatus.RECEIVED;
import static com.helpnest.domain.ticket.entity.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;

/**
 * PRD 5장 전이표 단위 테스트(docs/10 §3.4 필수 항목).
 *
 * <p>검증 전략: 허용 전이 7건과 각 전이의 수행자 집합을 <b>PRD 문서에서 직접 옮겨 적고</b>
 * (아래 {@code ALLOWED}), 불허 18건과 역할 불허 케이스는 그 리터럴에서 파생시킨다.
 * 프로덕션 전이표를 읽어와 비교하면 표가 틀려도 테스트가 통과하는 동어반복이 되기 때문이다.
 */
@DisplayName("TicketStateMachine — PRD 5장 티켓 상태 전이표")
class TicketStateMachineTest {

    private final TicketStateMachine stateMachine = new TicketStateMachine();

    /** 전이표 한 칸: {@code from → to} 와 그 칸의 수행자 집합. */
    private record Transition(TicketStatus from, TicketStatus to, Set<ActorRole> actors) {
    }

    /**
     * PRD 5장 전이표의 허용 칸 7건. 수행자는 전이표 수행자 열 원문을 따른다.
     * 재배정 2건에 AGENT 가 포함된 근거와 PRD 2.1 과의 충돌은 TicketStateMachine 주석 참고.
     */
    private static final List<Transition> ALLOWED = List.of(
            new Transition(RECEIVED, ASSIGNED, Set.of(SYSTEM, LEAD, ADMIN)),
            new Transition(ASSIGNED, ASSIGNED, Set.of(AGENT, LEAD, ADMIN)),
            new Transition(ASSIGNED, IN_PROGRESS, Set.of(AGENT, LEAD, ADMIN)),
            new Transition(IN_PROGRESS, ASSIGNED, Set.of(AGENT, LEAD, ADMIN)),
            new Transition(IN_PROGRESS, RESOLVED, Set.of(AGENT, LEAD, ADMIN)),
            new Transition(RESOLVED, IN_PROGRESS, Set.of(SYSTEM, CUSTOMER)),
            new Transition(RESOLVED, CLOSED, Set.of(SYSTEM, CUSTOMER)));

    // ------------------------------------------------------------------
    // 1. 전이 자체의 허용/불허 — 5 x 5 = 25 조합 전수
    // ------------------------------------------------------------------

    @DisplayName("전이표에 있는 7건은 허용된다")
    @ParameterizedTest(name = "{0} 에서 {1} 로 변경은 허용")
    @MethodSource("allowedTransitions")
    void isAllowed_returnsTrue_forTransitionsInTable(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.isAllowed(from, to)).isTrue();
    }

    @DisplayName("전이표에 없는 18건은 불허된다(제자리 전이·역행·건너뛰기·CLOSED 이후 포함)")
    @ParameterizedTest(name = "{0} 에서 {1} 로 변경은 불허")
    @MethodSource("deniedTransitions")
    void isAllowed_returnsFalse_forTransitionsNotInTable(TicketStatus from, TicketStatus to) {
        assertThat(stateMachine.isAllowed(from, to)).isFalse();
    }

    @DisplayName("허용 7건과 불허 18건을 합치면 전체 조합 25건이 되어 빠진 칸이 없다")
    @Test
    void allowedAndDeniedTogetherCoverEveryCombination() {
        List<Arguments> allowed = allowedTransitions().toList();
        List<Arguments> denied = deniedTransitions().toList();
        int statusCount = TicketStatus.values().length;

        assertThat(allowed).hasSize(7);
        assertThat(denied).hasSize(18);
        assertThat(allowed.size() + denied.size()).isEqualTo(statusCount * statusCount).isEqualTo(25);
    }

    // ------------------------------------------------------------------
    // 2. 수행자 역할 판정
    // ------------------------------------------------------------------

    @DisplayName("허용 전이를 전이표의 수행자가 시도하면 허용된다")
    @ParameterizedTest(name = "{2} 가 {0} 에서 {1} 로 변경 시도 시 허용")
    @MethodSource("allowedRoleCases")
    void isAllowedFor_returnsTrue_whenActorIsInTable(TicketStatus from, TicketStatus to, ActorRole actor) {
        assertThat(stateMachine.isAllowedFor(from, to, actor)).isTrue();
    }

    @DisplayName("허용 전이라도 전이표에 없는 역할이 시도하면 불허된다(예: 배정을 CUSTOMER 가 시도)")
    @ParameterizedTest(name = "{2} 가 {0} 에서 {1} 로 변경 시도 시 불허")
    @MethodSource("deniedRoleCases")
    void isAllowedFor_returnsFalse_whenActorIsNotInTable(TicketStatus from, TicketStatus to, ActorRole actor) {
        assertThat(stateMachine.isAllowedFor(from, to, actor)).isFalse();
    }

    @DisplayName("전이 자체가 불허면 어떤 역할이 시도해도 불허된다 — ADMIN 도 예외가 아니다")
    @ParameterizedTest(name = "{0} 이 RECEIVED 에서 CLOSED 로 건너뛰기를 시도해도 불허")
    @EnumSource(ActorRole.class)
    void isAllowedFor_returnsFalse_forDeniedTransition_evenForPrivilegedRoles(ActorRole actor) {
        assertThat(stateMachine.isAllowedFor(RECEIVED, CLOSED, actor)).isFalse();
    }

    @DisplayName("배정은 상담원이 스스로 할 수 없다 — RECEIVED 에서 ASSIGNED 로는 SYSTEM·LEAD·ADMIN 만")
    @Test
    void assignmentFromReceivedIsLimitedToSystemAndManagers() {
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, AGENT)).isFalse();
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, CUSTOMER)).isFalse();
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, SYSTEM)).isTrue();
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, LEAD)).isTrue();
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, ADMIN)).isTrue();
    }

    @DisplayName("해결 이후의 재문의·종료는 고객 행위여서 상담원·팀장·관리자가 대신할 수 없다")
    @Test
    void reopenAndCloseAreCustomerActions() {
        for (TicketStatus to : List.of(IN_PROGRESS, CLOSED)) {
            assertThat(stateMachine.isAllowedFor(RESOLVED, to, AGENT)).isFalse();
            assertThat(stateMachine.isAllowedFor(RESOLVED, to, LEAD)).isFalse();
            assertThat(stateMachine.isAllowedFor(RESOLVED, to, ADMIN)).isFalse();
            assertThat(stateMachine.isAllowedFor(RESOLVED, to, CUSTOMER)).isTrue();
            assertThat(stateMachine.isAllowedFor(RESOLVED, to, SYSTEM)).isTrue();
        }
    }

    @DisplayName("본인 담당 티켓인지는 판정하지 않는다 — 역할만 보므로 담당 검증은 TicketService 책임")
    @Test
    void ownershipIsNotCheckedHere() {
        // 남의 티켓을 든 상담원도 역할만으로는 통과한다. 이 경계는 S1 TicketService 가 메워야 한다.
        assertThat(stateMachine.isAllowedFor(ASSIGNED, IN_PROGRESS, AGENT)).isTrue();
        assertThat(stateMachine.isAllowedFor(IN_PROGRESS, RESOLVED, AGENT)).isTrue();
    }

    // ------------------------------------------------------------------
    // 3. allowedNextStatuses
    // ------------------------------------------------------------------

    @DisplayName("상태별 다음 상태 목록이 전이표와 일치한다")
    @ParameterizedTest(name = "{0} 의 다음 상태는 {1}")
    @MethodSource("nextStatusExpectations")
    void allowedNextStatuses_matchesTable(TicketStatus from, Set<TicketStatus> expected) {
        assertThat(stateMachine.allowedNextStatuses(from)).isEqualTo(expected);
    }

    @DisplayName("CLOSED 는 최종 상태여서 다음 상태가 없고 어떤 역할도 상태를 바꿀 수 없다")
    @Test
    void closedIsTerminal() {
        assertThat(stateMachine.allowedNextStatuses(CLOSED)).isEmpty();

        for (TicketStatus to : TicketStatus.values()) {
            assertThat(stateMachine.isAllowed(CLOSED, to)).isFalse();
            for (ActorRole actor : ActorRole.values()) {
                assertThat(stateMachine.isAllowedFor(CLOSED, to, actor)).isFalse();
            }
        }
    }

    @DisplayName("반환한 다음 상태 목록은 수정할 수 없어 전이표가 외부에서 오염되지 않는다")
    @Test
    void allowedNextStatusesIsUnmodifiable() {
        Set<TicketStatus> next = stateMachine.allowedNextStatuses(RECEIVED);

        assertThatThrownBy(() -> next.add(CLOSED)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(stateMachine.allowedNextStatuses(RECEIVED)).containsExactly(ASSIGNED);
    }

    // ------------------------------------------------------------------
    // 4. null 방어 — 판정기는 예외를 던지지 않고 불허로 답한다
    // ------------------------------------------------------------------

    @DisplayName("인자가 null 이면 예외 대신 불허로 답한다")
    @Test
    void nullArgumentsAreTreatedAsDenied() {
        assertThat(stateMachine.isAllowed(null, ASSIGNED)).isFalse();
        assertThat(stateMachine.isAllowed(RECEIVED, null)).isFalse();
        assertThat(stateMachine.isAllowed(null, null)).isFalse();
        assertThat(stateMachine.isAllowedFor(RECEIVED, ASSIGNED, null)).isFalse();
        assertThat(stateMachine.isAllowedFor(null, null, ADMIN)).isFalse();
        assertThat(stateMachine.allowedNextStatuses(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // MethodSource — 모두 ALLOWED 리터럴에서만 파생시킨다
    // ------------------------------------------------------------------

    private static Stream<Arguments> allowedTransitions() {
        return ALLOWED.stream().map(t -> Arguments.of(t.from(), t.to()));
    }

    /** 전체 25조합에서 허용 7건을 뺀 나머지. 칸을 빼먹지 않도록 열거하지 않고 계산한다. */
    private static Stream<Arguments> deniedTransitions() {
        List<Arguments> denied = new ArrayList<>();
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus to : TicketStatus.values()) {
                boolean inTable = ALLOWED.stream().anyMatch(t -> t.from() == from && t.to() == to);
                if (!inTable) {
                    denied.add(Arguments.of(from, to));
                }
            }
        }
        return denied.stream();
    }

    private static Stream<Arguments> allowedRoleCases() {
        return ALLOWED.stream()
                .flatMap(t -> t.actors().stream().map(actor -> Arguments.of(t.from(), t.to(), actor)));
    }

    /** 허용 전이마다 수행자 집합의 여집합. 5개 역할 중 3개가 허용이므로 전이당 2건씩 나온다. */
    private static Stream<Arguments> deniedRoleCases() {
        return ALLOWED.stream().flatMap(t -> EnumSet.complementOf(EnumSet.copyOf(t.actors())).stream()
                .map(actor -> Arguments.of(t.from(), t.to(), actor)));
    }

    private static Stream<Arguments> nextStatusExpectations() {
        return Stream.of(
                Arguments.of(RECEIVED, Set.of(ASSIGNED)),
                Arguments.of(ASSIGNED, Set.of(ASSIGNED, IN_PROGRESS)),
                Arguments.of(IN_PROGRESS, Set.of(ASSIGNED, RESOLVED)),
                Arguments.of(RESOLVED, Set.of(IN_PROGRESS, CLOSED)),
                Arguments.of(CLOSED, Set.of()));
    }
}
