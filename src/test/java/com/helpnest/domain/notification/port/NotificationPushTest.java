// @owner PMJ
package com.helpnest.domain.notification.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.helpnest.domain.notification.dto.NotificationPayload;
import com.helpnest.domain.notification.entity.Notification;
import com.helpnest.domain.notification.entity.NotificationType;
import com.helpnest.domain.notification.repository.NotificationRepository;

/**
 * 알림 실시간 발송 단위 테스트 (docs/02 §6, docs/04 §11).
 *
 * <p>저장 동작은 실제 DB 를 쓰는 {@code NotificationAdapterTest} 가 보고, 여기서는 발송만
 * mock 으로 본다. <b>{@code @MockitoBean} 으로 스프링 컨텍스트를 하나 더 만들지 않기 위해</b>
 * 어댑터를 직접 생성한다 — 컨텍스트가 늘면 Hikari 풀도 같이 늘어 PostgreSQL
 * {@code max_connections} 를 넘기고 남의 테스트가 깨진다({@code NotificationAdapterTest} 주석).
 *
 * <p>커밋 지연은 {@link TransactionSynchronizationManager} 를 직접 열고 닫아 확인한다.
 * 실제 트랜잭션을 걸면 "발송이 커밋 전에 나갔는지"를 관찰할 틈이 없다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationAdapter — /user/queue/notifications 실시간 발송")
class NotificationPushTest {

    private static final Long RECEIVER_ID = 7L;

    @Mock
    NotificationRepository notificationRepository;
    @Mock
    SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    NotificationAdapter adapter;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 저장된 알림을 흉내 내는 스텁. id·createdAt 은 DB 가 채우므로 payload 검증용으로만 쓴다 */
    private Notification givenSaved(Long ticketId, String message) {
        Notification saved = Notification.builder()
                .receiverId(RECEIVER_ID)
                .type(NotificationType.ASSIGNED)
                .ticketId(ticketId)
                .message(message)
                .build();
        when(notificationRepository.save(any())).thenReturn(saved);
        return saved;
    }

    @Test
    @DisplayName("트랜잭션 밖이면 즉시 개인 큐로 보낸다 — 목적지에 /user 를 붙이지 않는다")
    void pushesImmediatelyOutsideTransaction() {
        givenSaved(10L, "새 티켓이 배정되었습니다.");

        adapter.notify(RECEIVER_ID, "ASSIGNED", 10L, "새 티켓이 배정되었습니다.");

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/notifications"), payload.capture());
        assertThat(payload.getValue().type()).isEqualTo("ASSIGNED");
        assertThat(payload.getValue().ticketId()).isEqualTo(10L);
        assertThat(payload.getValue().message()).isEqualTo("새 티켓이 배정되었습니다.");
    }

    /**
     * 커밋 전에 보내면 호출자가 뒤에서 롤백했을 때 벨에는 떠 있고 REST 조회에는 없는 알림이
     * 된다. 롤백되면 {@code afterCommit} 이 호출되지 않아 그 상태가 아예 생기지 않는다.
     */
    @Test
    @DisplayName("트랜잭션 안이면 커밋 이후로 미룬다 — 롤백되면 아예 보내지 않는다")
    void defersPushUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        givenSaved(10L, "새 티켓이 배정되었습니다.");

        adapter.notify(RECEIVER_ID, "ASSIGNED", 10L, "새 티켓이 배정되었습니다.");

        verifyNoInteractions(messagingTemplate);   // 아직 커밋 전
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq("/queue/notifications"), any(Object.class));
    }

    @Test
    @DisplayName("발송이 실패해도 예외가 호출자에게 가지 않는다 — 행은 남아 REST 로 조회된다")
    void swallowsPushFailure() {
        givenSaved(null, "미배정 티켓이 있습니다.");
        doThrow(new IllegalStateException("broker down"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> adapter.notify(RECEIVER_ID, "UNASSIGNED", null, "미배정 티켓이 있습니다."))
                .doesNotThrowAnyException();

        verify(notificationRepository).save(any());
    }

    @Test
    @DisplayName("저장이 실패하면 발송하지 않는다 — 보낼 내용 자체가 없다")
    void doesNotPushWhenSaveFails() {
        when(notificationRepository.save(any())).thenThrow(new IllegalStateException("fk violation"));

        assertThatCode(() -> adapter.notify(RECEIVER_ID, "ASSIGNED", 10L, "문구"))
                .doesNotThrowAnyException();

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("걸러지는 입력은 저장도 발송도 하지 않는다")
    void rejectedInputNeitherSavesNorPushes() {
        adapter.notify(null, "ASSIGNED", 10L, "수신자 없음");
        adapter.notify(RECEIVER_ID, "NOT_A_TYPE", 10L, "알 수 없는 종류");
        adapter.notify(RECEIVER_ID, "ASSIGNED", 10L, "  ");

        verifyNoInteractions(notificationRepository, messagingTemplate);
    }
}
