// @owner PMJ
package com.helpnest.domain.chat.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.helpnest.domain.chat.entity.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 이전 메시지 커서 조회(GET /api/chat/rooms/{id}/messages?before=). 최신순으로 size 건을
     * 읽으므로 화면에 붙일 때는 호출자가 뒤집는다. id 는 BIGSERIAL 이라 작성 순서와 같다.
     */
    List<ChatMessage> findByRoomIdAndIdLessThanOrderByIdDesc(Long roomId, Long beforeId, Pageable pageable);

    /** before 없이 최신 size 건. */
    List<ChatMessage> findByRoomIdOrderByIdDesc(Long roomId, Pageable pageable);

    /** 대기 중 메시지 전체 — 전환·연결 시 티켓 본문이 된다(04 §10). */
    List<ChatMessage> findByRoomIdOrderByIdAsc(Long roomId);
}
