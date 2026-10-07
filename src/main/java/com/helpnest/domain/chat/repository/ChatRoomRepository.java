// @owner PMJ
package com.helpnest.domain.chat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    List<ChatRoom> findByCustomerIdOrderByIdDesc(Long customerId);

    List<ChatRoom> findByAgentIdOrderByIdDesc(Long agentId);

    /** STOMP SUBSCRIBE·SEND 검증용. 방 전체를 읽지 않고 참여 여부만 본다. */
    @Query("""
            select count(r) > 0 from ChatRoom r
            where r.id = :roomId and (r.customerId = :memberId or r.agentId = :memberId)
            """)
    boolean existsParticipant(@Param("roomId") Long roomId, @Param("memberId") Long memberId);
}
