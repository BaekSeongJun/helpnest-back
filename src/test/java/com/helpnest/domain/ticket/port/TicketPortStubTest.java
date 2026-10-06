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
 * <p><b>S2 현재 상태:</b> 네 포트 모두 실구현으로 교체됐다. 그래서 각 검증은 "호출해도 터지지
 * 않음"이 아니라 그 포트의 실제 계약을 본다. 실제 동작은 각각
 * {@code TicketClassificationFlowTest}·{@code CustomerTicketApiTest}·{@code TicketGuestPortTest}
 * ·{@code NotificationAdapterTest} 가 실제 데이터로 검증하고, 여기서는 배선만 본다.
 *
 * <p>이 클래스에는 {@code @Transactional} 이 없어 저장이 실제로 커밋된다. 그래서 알림 검증도
 * <b>행이 남지 않는 입력</b>만 쓴다 — 저장되는 경로는 {@code NotificationAdapterTest} 담당이다.
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

    /**
     * 조회 포트도 S1 에서 실구현으로 바뀌었다. 없는 티켓에 null 을 돌려주면 비동기 분류
     * 리스너가 "분류할 내용이 없다"와 "티켓이 사라졌다"를 구분할 수 없으므로 예외를 던진다.
     * 유형이 null 이면 빈 목록인 것은 유지된다 — 유형 없이 전체에서 고르면 엉뚱한 분야의
     * 답변이 초안에 섞인다.
     */
    @Test
    @DisplayName("조회 포트는 없는 티켓에 TICKET_NOT_FOUND 를 주고 유형 없는 조회에 빈 List 를 준다")
    void 조회_포트_반환() {
        assertThatThrownBy(() -> queryPort.getTicketSummary(-1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().code())
                        .isEqualTo("TICKET_NOT_FOUND"));
        assertThat(queryPort.findResolvedReplies(null, 3)).isEmpty();
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

    /**
     * S2 에서 알림 포트가 저장 실구현으로 바뀌면서 이 검증의 내용도 바뀌었다. 스텁 시절에는
     * {@code notify(1L, "ASSIGNED", 10L, ...)} 처럼 아무 식별자나 넘겨도 무해했지만, 실구현은
     * receiver_id·ticket_id 를 FK 로 저장하므로 <b>없는 id 를 넘기면 호출자 트랜잭션이 함께
     * 롤백된다</b>({@code NotificationAdapter} 클래스 주석). 즉 "아무 값이나 괜찮다"는 전제가
     * 사라졌고, 호출자는 자기가 다루는 실제 id 만 넘겨야 한다.
     *
     * <p>그래서 여기서는 어댑터가 DB 에 닿기 전에 걸러 내는 입력만 확인한다 — 배선 검증에 필요한
     * 것은 "포트를 호출할 수 있다"이고, 저장되는 경로는 실제 회원·티켓을 만드는
     * {@code NotificationAdapterTest} 가 본다.
     */
    @Test
    @DisplayName("알림 포트는 저장 실구현이다 — 걸러지는 입력은 예외 없이 무시된다")
    void 알림_포트_호출() {
        assertThatCode(() -> {
            notificationPort.notify(null, "ASSIGNED", null, "수신자가 없는 알림은 버린다");
            notificationPort.notify(1L, "NOT_A_REAL_TYPE", null, "8종에 없는 종류는 버린다");
            notificationPort.notify(1L, "CHAT_REQUEST", null, "   ");
        }).doesNotThrowAnyException();
    }
}
