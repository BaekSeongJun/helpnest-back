// @owner PMJ
package com.helpnest.domain.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import com.helpnest.domain.chat.entity.ChatMessage;
import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;
import com.helpnest.domain.ticket.service.TicketNoGenerator;

import jakarta.persistence.EntityManager;

/**
 * 실제 PostgreSQL 에서 Flyway 로 만든 chat 테이블과 조회 메서드가 맞물리는지 확인한다.
 * member FK 때문에 시드 계정을 이메일로 찾아 쓴다(R__seed_BSJ_member.sql 주석 규칙).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// 쓰지 않는 Import 지만 TicketNoGeneratorDbTest 와 컨텍스트 키를 맞춰 캐시를 공유한다.
// 새 컨텍스트마다 Hikari 풀(10개)이 열려 로컬 PG max_connections(100)를 넘기기 때문이다.
@Import(TicketNoGenerator.class)
@DisplayName("ChatRoomRepository·ChatMessageRepository — DB 연동")
class ChatRoomRepositoryDbTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-15T10:00:00+09:00");

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private EntityManager em;

    private Long customer1;
    private Long customer2;
    private Long agent1;

    @BeforeEach
    void setUp() {
        customer1 = memberId("customer1@helpnest.local");
        customer2 = memberId("customer2@helpnest.local");
        agent1 = memberId("agent1@helpnest.local");
    }

    @Test
    @DisplayName("WAITING 방은 queued_at 순(FIFO)으로 나오고 OPEN 방은 빠진다")
    void 대기열_FIFO() {
        ChatRoom later = roomRepository.save(ChatRoom.waiting(customer1, NOW.plusSeconds(30)));
        ChatRoom first = roomRepository.save(ChatRoom.waiting(customer2, NOW));
        ChatRoom opened = ChatRoom.waiting(customer1, NOW.minusMinutes(1));
        opened.open(agent1, null, NOW);
        roomRepository.save(opened);

        List<ChatRoom> queue = roomRepository.findByStatusOrderByQueuedAtAscIdAsc(ChatRoomStatus.WAITING);

        // 다른 테스트 데이터가 남아 있어도 이 두 방의 상대 순서만 본다
        assertThat(queue).extracting(ChatRoom::getId)
                .containsSubsequence(first.getId(), later.getId())
                .doesNotContain(opened.getId());
    }

    @Test
    @DisplayName("진행 중 방 조회는 WAITING·OPEN 만 찾는다")
    void 진행중_방() {
        ChatRoom canceled = ChatRoom.waiting(customer1, NOW);
        canceled.cancel(NOW);
        roomRepository.save(canceled);

        assertThat(roomRepository.findFirstByCustomerIdAndStatusIn(customer1,
                EnumSet.of(ChatRoomStatus.WAITING, ChatRoomStatus.OPEN))
                .filter(r -> r.getId().equals(canceled.getId()))).isEmpty();
    }

    @Test
    @DisplayName("참여자는 고객·담당 상담원만, 다른 회원은 false")
    void 참여자_조회() {
        ChatRoom room = ChatRoom.waiting(customer1, NOW);
        room.open(agent1, null, NOW);
        Long roomId = roomRepository.save(room).getId();

        assertThat(roomRepository.existsParticipant(roomId, customer1)).isTrue();
        assertThat(roomRepository.existsParticipant(roomId, agent1)).isTrue();
        assertThat(roomRepository.existsParticipant(roomId, customer2)).isFalse();
    }

    @Test
    @DisplayName("메시지 커서 조회는 before 보다 작은 id 를 최신순으로 size 건 돌려준다")
    void 메시지_커서() {
        Long roomId = roomRepository.save(ChatRoom.waiting(customer1, NOW)).getId();
        List<Long> ids = List.of("1", "2", "3", "4").stream()
                .map(c -> messageRepository.save(new ChatMessage(roomId, customer1, c)).getId())
                .toList();

        List<ChatMessage> page = messageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(
                roomId, ids.get(3), PageRequest.ofSize(2));

        assertThat(page).extracting(ChatMessage::getContent).containsExactly("3", "2");
        assertThat(messageRepository.findByRoomIdOrderByIdAsc(roomId))
                .extracting(ChatMessage::getContent).containsExactly("1", "2", "3", "4");
    }

    private Long memberId(String email) {
        return ((Number) em.createNativeQuery("SELECT member_id FROM member WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
