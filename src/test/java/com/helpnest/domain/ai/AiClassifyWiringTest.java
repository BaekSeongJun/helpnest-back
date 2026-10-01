// @owner SSJ
package com.helpnest.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.helpnest.domain.ai.entity.TicketAiResult;
import com.helpnest.domain.ai.listener.AiClassifyListener;
import com.helpnest.domain.ai.port.AiResultAdapter;
import com.helpnest.domain.ai.repository.TicketAiResultRepository;
import com.helpnest.domain.ai.service.ClassifyService;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiClassifyWiringTest {

    @Test
    @DisplayName("TicketCreatedEvent → ClassifyService.classify(ticketId)")
    void listenerDelegates() {
        ClassifyService service = mock(ClassifyService.class);

        new AiClassifyListener(service).on(new TicketCreatedEvent(3L));

        verify(service).classify(3L);
    }

    @Test
    @DisplayName("markOverridden → overridden_by 기록, 결과 없으면 무시")
    void markOverridden() {
        TicketAiResultRepository repository = mock(TicketAiResultRepository.class);
        TicketAiResult result = TicketAiResult.builder().ticketId(3L).status(TicketAiResult.Status.SUCCESS).build();
        when(repository.findByTicketId(3L)).thenReturn(Optional.of(result));
        when(repository.findByTicketId(4L)).thenReturn(Optional.empty());
        AiResultAdapter adapter = new AiResultAdapter(repository);

        adapter.markOverridden(3L, 11L, "REFUND", "HIGH");
        adapter.markOverridden(4L, 11L, "REFUND", "HIGH");

        assertThat(result.getOverriddenBy()).isEqualTo(11L);
    }
}
