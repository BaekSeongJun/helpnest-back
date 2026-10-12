// @owner PMJ
package com.helpnest.domain.chat.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;

import jakarta.persistence.LockModeType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /**
     * 매칭·전환·종료용 비관적 락 조회. 대기열 스케줄러와 고객의 "나가기"·"문의로 남기기"가
     * 같은 WAITING 방을 동시에 바꿀 수 있어 방 1행을 잠가 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ChatRoom r where r.id = :id")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

    /** 대기열 FIFO. 순번은 이 목록의 인덱스 + 1 이다(docs/03 §4.7 과 같은 기준). */
    List<ChatRoom> findByStatusOrderByQueuedAtAscIdAsc(ChatRoomStatus status);

    /** 고객의 진행 중 방(WAITING·OPEN). 채팅 요청을 중복으로 눌러도 방은 하나만 쓴다. */
    Optional<ChatRoom> findFirstByCustomerIdAndStatusIn(Long customerId, Collection<ChatRoomStatus> statuses);

    /**
     * 진행 중 방이 없을 때만 WAITING 방을 넣는다. 부분 유니크 인덱스(uq_chat_room_active_customer)와
     * ON CONFLICT 로, 동시 요청 두 건 중 늦은 쪽은 앞 트랜잭션의 커밋을 기다렸다가 아무것도 하지 않는다.
     *
     * @return 넣었으면 1, 이미 있었으면 0
     */
    @Modifying
    @Query(value = """
            insert into chat_room (customer_id, status, queued_at)
            values (:customerId, 'WAITING', :queuedAt)
            on conflict (customer_id) where status in ('WAITING', 'OPEN') do nothing
            """, nativeQuery = true)
    int insertWaitingIfAbsent(@Param("customerId") Long customerId, @Param("queuedAt") OffsetDateTime queuedAt);

    /** 목록 화면용 최근 50개. 오래된 상담은 티켓으로 남아 있어 여기서 끝까지 보여 줄 필요가 없다 */
    List<ChatRoom> findTop50ByCustomerIdOrderByIdDesc(Long customerId);

    List<ChatRoom> findTop50ByAgentIdOrderByIdDesc(Long agentId);

    /** STOMP SUBSCRIBE·SEND 검증용. 방 전체를 읽지 않고 참여 여부만 본다. */
    @Query("""
            select count(r) > 0 from ChatRoom r
            where r.id = :roomId and (r.customerId = :memberId or r.agentId = :memberId)
            """)
    boolean existsParticipant(@Param("roomId") Long roomId, @Param("memberId") Long memberId);
}
