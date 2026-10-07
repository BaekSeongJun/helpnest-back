// @owner PMJ
package com.helpnest.domain.chat.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.helpnest.domain.chat.scheduler.ChatQueueScheduler;
import com.helpnest.domain.member.event.AgentAvailabilityChangedEvent;

@DisplayName("ChatQueueListener — 상담 가능 전환 시 대기열 즉시 처리")
class ChatQueueListenerTest {

    private final ChatQueueScheduler chatQueue = mock(ChatQueueScheduler.class);
    private final ChatQueueListener listener = new ChatQueueListener(chatQueue);

    @Test
    @DisplayName("ON 으로 바뀌면 대기열을 한 번 돌린다")
    void ON() {
        listener.onAvailabilityChanged(new AgentAvailabilityChangedEvent(3L, true));
        verify(chatQueue).run();
    }

    @Test
    @DisplayName("OFF 로 바뀌면 아무것도 하지 않는다")
    void OFF() {
        listener.onAvailabilityChanged(new AgentAvailabilityChangedEvent(3L, false));
        verify(chatQueue, never()).run();
    }
}
