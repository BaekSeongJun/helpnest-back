// @owner PMJ
package com.helpnest.domain.ticket.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.helpnest.domain.notification.port.NotificationPort;
import com.helpnest.global.error.BusinessException;

/**
 * 박민재 소유 포트 4종의 S0 스텁이 스프링 컨텍스트에 주입되고 호출해도 터지지 않는지 확인한다.
 *
 * <p>S0 의 목표는 신수진·백성준이 S1 에 이 포트를 호출하는 코드를 <b>컴파일하고 실행</b>할 수
 * 있게 하는 것이다. 따라서 검증할 것은 동작이 아니라 배선이다. 실제 반영 여부는 S1·S2 에서
 * 구현과 함께 테스트한다.
 *
 * <p>인터페이스 타입으로 주입받는 것이 핵심이다. 구현체 타입으로 받으면 S1 에 구현이 교체될 때
 * 테스트가 함께 깨져 배선 검증의 의미가 없어진다.
 *
 * <p><b>S1 현재 상태:</b> 분류 포트와 비회원 조회 2건(verifyGuest, findGuestPasswordHash)은
 * 실구현으로 교체됐고 조회 포트와 updateGuestPassword 는 아직 스텁이다. 그래서 검증 내용이
 * 포트마다 다르다 — 실구현 포트는 "실제 계약"을, 스텁 포트는 "호출해도 터지지 않음"을 본다.
 * 비회원 포트의 실제 동작은 {@code TicketGuestPortTest} 가 실제 티켓으로 검증한다.
 */
@SpringBootTest
@DisplayName("박민재 포트 S0 스텁 — 배선과 호출 가능성")
class TicketPortStubTest {

    @Autowired
    private TicketClassificationPort classificationPort;

    @Autowired
    private TicketQueryPort queryPort;

    @Autowired
    private TicketGuestPort guestPort;

    @Autowired
    private NotificationPort notificationPort;

    @Test
    @DisplayName("포트 4종이 모두 빈으로 등록되어 인터페이스 타입으로 주입된다")
    void 포트_4종_주입() {
        assertThat(classificationPort).isNotNull();
        assertThat(queryPort).isNotNull();
        assertThat(guestPort).isNotNull();
        assertThat(notificationPort).isNotNull();
    }

    /**
     * S1 에서 분류 포트가 실구현으로 바뀌면서 이 검증의 내용도 바뀌었다. 스텁 시절에는 어떤
     * ticketId 로 불러도 예외가 없는 것이 "신수진 S1 작업의 전제"였지만, 실구현은 없는 티켓을
     * 조용히 넘기면 안 된다 — 분류 결과가 사라졌는데 호출자가 알 수 없게 된다.
     *
     * <p>반영 동작 자체는 {@code TicketClassificationFlowTest} 가 실제 티켓으로 검증한다.
     * 여기서는 배선(인터페이스 주입 → 실구현 도달)만 확인한다.
     */
    @Test
    @DisplayName("분류 반영 포트는 없는 티켓에 TICKET_NOT_FOUND 를 준다 — 실구현에 도달한다는 뜻")
    void 분류_포트_호출() {
        long missingTicketId = -1L;

        assertThatThrownBy(() -> classificationPort.applyClassification(
                missingTicketId, "DELIVERY", "URGENT", "NEGATIVE"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                        .isEqualTo("TICKET_NOT_FOUND"));

        assertThatThrownBy(() -> classificationPort.applyClassificationFailed(missingTicketId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("조회 포트는 요약에 null, 과거 답변에 빈 List 를 준다")
    void 조회_포트_반환() {
        assertThat(queryPort.getTicketSummary(1L)).isNull();
        assertThat(queryPort.findResolvedReplies("DELIVERY", 3)).isEmpty();
    }

    /**
     * verifyGuest·findGuestPasswordHash 는 CR #34 로 실구현됐다. 없는 조합에 null 을 준다는
     * 결과는 스텁 시절과 같지만 의미가 다르다 — 미구현이 아니라 "조회했는데 없다"는 뜻이다.
     * 실제로 찾아내는지는 TicketGuestPortTest 가 티켓을 만들어 검증한다.
     */
    @Test
    @DisplayName("비회원 포트는 없는 조합에 null 을 주고 비밀번호 변경은 예외 없이 무시된다")
    void 비회원_포트() {
        assertThat(guestPort.verifyGuest("HN-20261002-000123", "guest@example.com")).isNull();
        assertThat(guestPort.findGuestPasswordHash(-1L)).isNull();
        assertThatCode(() -> guestPort.updateGuestPassword(1L, "$2a$10$dummy"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("알림 포트를 호출해도 예외가 나지 않는다 — ticketId 가 null 인 알림도 허용한다")
    void 알림_포트_호출() {
        assertThatCode(() -> {
            notificationPort.notify(1L, "ASSIGNED", 10L, "새 티켓이 배정되었습니다.");
            notificationPort.notify(1L, "CHAT_REQUEST", null, "채팅 상담 요청이 있습니다.");
        }).doesNotThrowAnyException();
    }
}
