// @owner PMJ
package com.helpnest.domain.ticket.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.helpnest.domain.notification.port.NotificationPort;

/**
 * 박민재 소유 포트 4종의 S0 스텁이 스프링 컨텍스트에 주입되고 호출해도 터지지 않는지 확인한다.
 *
 * <p>S0 의 목표는 신수진·백성준이 S1 에 이 포트를 호출하는 코드를 <b>컴파일하고 실행</b>할 수
 * 있게 하는 것이다. 따라서 검증할 것은 동작이 아니라 배선이다. 실제 반영 여부는 S1·S2 에서
 * 구현과 함께 테스트한다.
 *
 * <p>인터페이스 타입으로 주입받는 것이 핵심이다. 구현체 타입으로 받으면 S1 에 구현이 교체될 때
 * 테스트가 함께 깨져 배선 검증의 의미가 없어진다.
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

    @Test
    @DisplayName("분류 반영 포트를 호출해도 예외가 나지 않는다 — 신수진 S1 작업의 전제")
    void 분류_포트_호출() {
        assertThatCode(() -> {
            classificationPort.applyClassification(1L, "DELIVERY", "URGENT", "NEGATIVE");
            classificationPort.applyClassificationFailed(1L);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("조회 포트는 요약에 null, 과거 답변에 빈 List 를 준다")
    void 조회_포트_반환() {
        assertThat(queryPort.getTicketSummary(1L)).isNull();
        assertThat(queryPort.findResolvedReplies("DELIVERY", 3)).isEmpty();
    }

    @Test
    @DisplayName("비회원 확인 포트는 null 을 주고 비밀번호 변경은 예외 없이 무시된다")
    void 비회원_포트() {
        assertThat(guestPort.verifyGuest("HN-20261002-000123", "guest@example.com")).isNull();
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
