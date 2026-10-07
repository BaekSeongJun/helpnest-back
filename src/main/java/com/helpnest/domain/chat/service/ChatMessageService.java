// @owner PMJ
package com.helpnest.domain.chat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.chat.dto.ChatMessageResponse;
import com.helpnest.domain.chat.dto.ChatRoomResponse;
import com.helpnest.domain.chat.entity.ChatMessage;
import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;
import com.helpnest.domain.chat.error.ChatErrorCode;
import com.helpnest.domain.chat.repository.ChatMessageRepository;
import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.repository.MemberName;
import com.helpnest.domain.ticket.repository.MemberNameLookupRepository;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 대화 — 메시지 송신·이전 메시지·방 목록 (FR-CHT-02·06, docs/04 §10·§11).
 * 방의 생명주기(요청·연결·전환·종료)는 {@link ChatService} 가 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private static final int MAX_CONTENT_LENGTH = 5_000;
    private static final int MAX_PAGE_SIZE = 100;

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final MemberNameLookupRepository memberNameLookupRepository;
    private final TicketService ticketService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 메시지를 저장하고 방 구독자에게 보낸다 (STOMP /app/chat/{roomId}/send).
     *
     * <p>WAITING 에서도 고객은 보낼 수 있다 — 대기 중 메시지가 연결·전환 시 티켓 본문이 된다(docs/04 §10).
     * WAITING 방의 참여자는 고객뿐이라 별도 분기가 필요 없다.
     *
     * <p>담당 상담원의 메시지는 첫 응답으로 기록하고 ASSIGNED→IN_PROGRESS 로 전이한다(FR-CHT-06).
     * 매 메시지마다 부르지만 첫 응답 시각은 한 번만 남고 전이도 ASSIGNED 일 때만 일어난다.
     */
    @Transactional
    public ChatMessageResponse send(Long roomId, Long senderId, String rawContent) {
        ChatRoom room = participantRoom(roomId, senderId);
        if (room.getStatus() != ChatRoomStatus.WAITING && room.getStatus() != ChatRoomStatus.OPEN) {
            throw new BusinessException(ChatErrorCode.INVALID_STATE);
        }
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ChatErrorCode.INVALID_CONTENT);
        }

        ChatMessage message = messageRepository.save(new ChatMessage(roomId, senderId, content));
        if (senderId.equals(room.getAgentId())) {
            ticketService.recordFirstResponse(room.getTicketId(), senderId, ActorRole.AGENT);
        }

        ChatMessageResponse response = toResponse(message, memberNameLookupRepository.findName(senderId));
        AfterCommit.send(() -> messagingTemplate.convertAndSend("/topic/chat/" + roomId, response));
        return response;
    }

    /**
     * 이전 메시지 (GET /api/chat/rooms/{roomId}/messages?before=&size=). 화면에 그대로 붙이도록
     * 오래된 것부터 돌려준다. 재접속 시 before 없이 부르면 최신 size 건이다(FR-CHT-02).
     */
    public List<ChatMessageResponse> messages(Long roomId, Long memberId, Long before, int size) {
        participantRoom(roomId, memberId);
        PageRequest page = PageRequest.ofSize(Math.clamp(size, 1, MAX_PAGE_SIZE));
        List<ChatMessage> latestFirst = before == null
                ? messageRepository.findByRoomIdOrderByIdDesc(roomId, page)
                : messageRepository.findByRoomIdAndIdLessThanOrderByIdDesc(roomId, before, page);

        List<ChatMessage> ordered = new ArrayList<>(latestFirst);
        Collections.reverse(ordered);
        Map<Long, String> names = names(ordered.stream().map(ChatMessage::getSenderId));
        return ordered.stream().map(m -> toResponse(m, names.get(m.getSenderId()))).toList();
    }

    /** 방 목록 (GET /api/chat/rooms). 고객은 본인 방, 그 외(상담원)는 담당 방이다 */
    public List<ChatRoomResponse> rooms(Long memberId, boolean customer) {
        List<ChatRoom> rooms = customer
                ? roomRepository.findTop50ByCustomerIdOrderByIdDesc(memberId)
                : roomRepository.findTop50ByAgentIdOrderByIdDesc(memberId);
        Map<Long, String> names = names(rooms.stream()
                .flatMap(r -> Stream.of(r.getCustomerId(), r.getAgentId())));
        return rooms.stream()
                .map(r -> new ChatRoomResponse(r.getId(), r.getStatus(), r.getTicketId(), r.getCustomerId(),
                        names.get(r.getCustomerId()), names.get(r.getAgentId()), r.getQueuedAt(),
                        r.getOpenedAt(), r.getClosedAt()))
                .toList();
    }

    /** 참여자가 아니면 방이 있어도 403 — 다른 사람 대화를 읽거나 쓰는 것을 막는 유일한 지점이다 */
    private ChatRoom participantRoom(Long roomId, Long memberId) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_FOUND));
        if (!room.isParticipant(memberId)) {
            throw new BusinessException(ChatErrorCode.NOT_PARTICIPANT);
        }
        return room;
    }

    /** 이름을 한 번에 읽는다 (N+1 방지, {@link MemberNameLookupRepository} 주석) */
    private Map<Long, String> names(Stream<Long> memberIds) {
        Set<Long> ids = memberIds.filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return memberNameLookupRepository.findNames(ids).stream()
                .collect(Collectors.toMap(MemberName::getMemberId, MemberName::getName));
    }

    private static ChatMessageResponse toResponse(ChatMessage m, String senderName) {
        return new ChatMessageResponse(m.getId(), m.getSenderId(), senderName, m.getContent(), m.getCreatedAt());
    }
}
