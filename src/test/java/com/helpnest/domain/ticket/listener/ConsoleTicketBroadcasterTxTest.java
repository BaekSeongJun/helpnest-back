// @owner PMJ
package com.helpnest.domain.ticket.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.domain.ticket.dto.TicketCreateRequest;
import com.helpnest.domain.ticket.service.TicketService;

/**
 * 커밋 전에는 신호가 나가지 않는다 — 테스트 트랜잭션은 커밋 없이 롤백되므로, 접수가 끝난 뒤에도
 * 브로커 채널에 콘솔 신호가 없어야 한다(롤백된 접수는 알리지 않음). 커밋 후 실제 발송은 실서버로 확인했다.
 *
 * <p>모킹 빈({@code @MockitoBean})을 쓰지 않는다 — 새 컨텍스트가 생기면 Hikari 풀이 하나 더 열려
 * 로컬 PG 연결 한도를 넘긴다. 대신 실제 brokerChannel 에 인터셉터를 잠시 붙여 엿본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DisplayName("ConsoleTicketBroadcaster — 커밋 전 미발송")
class ConsoleTicketBroadcasterTxTest {

    @Autowired
    TicketService ticketService;
    @Autowired
    MemberRepository memberRepository;
    @Autowired
    @Qualifier("brokerChannel")
    MessageChannel brokerChannel;

    private final List<String> destinations = new CopyOnWriteArrayList<>();
    private final ChannelInterceptor spy = new ChannelInterceptor() {
        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            destinations.add(SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
            return message;
        }
    };

    @BeforeEach
    void attach() {
        ((AbstractSubscribableChannel) brokerChannel).addInterceptor(spy);
    }

    @AfterEach
    void detach() {
        ((AbstractSubscribableChannel) brokerChannel).removeInterceptor(spy);
    }

    @Test
    @DisplayName("접수 트랜잭션이 커밋되지 않으면 /topic/console/tickets 로 아무것도 보내지 않는다")
    void 커밋_전_미발송() {
        Long customerId = memberRepository.findByEmail("customer1@helpnest.local").orElseThrow().getId();
        ticketService.create(new TicketCreateRequest("배송 문의", "아직 안 왔어요", null, null, null), customerId);

        assertThat(destinations).doesNotContain(ConsoleTicketBroadcaster.DESTINATION);
    }
}
