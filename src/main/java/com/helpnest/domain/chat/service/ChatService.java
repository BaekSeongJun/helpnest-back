// @owner PMJ
package com.helpnest.domain.chat.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.helpnest.domain.assignment.error.AssignErrorCode;
import com.helpnest.domain.assignment.service.AssignmentService;
import com.helpnest.domain.chat.dto.ChatConvertResponse;
import com.helpnest.domain.chat.dto.ChatStatusPayload;
import com.helpnest.domain.chat.entity.ChatMessage;
import com.helpnest.domain.chat.entity.ChatRoom;
import com.helpnest.domain.chat.entity.ChatRoomStatus;
import com.helpnest.domain.chat.error.ChatErrorCode;
import com.helpnest.domain.chat.repository.ChatMessageRepository;
import com.helpnest.domain.chat.repository.ChatRoomRepository;
import com.helpnest.domain.member.port.MemberQueryPort;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.service.TicketService;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 요청·대기열·전환 (FR-CHT-01·04·05).
 *
 * <h2>연결 시도는 요청 트랜잭션과 분리한다</h2>
 * {@link #tryMatch} 는 방 생성({@link #openOrGetRoom})과 다른 트랜잭션이다. 가용 상담원을 확인한
 * 직후 그 상담원이 OFF 하면 {@code autoAssign} 이 null 을 주고, 그때는 만들던 CHAT 티켓까지
 * 롤백해야 한다. 같은 트랜잭션이면 방 생성도 함께 롤백돼 고객이 요청 실패를 보게 되므로,
 * 방은 먼저 WAITING 으로 커밋하고 연결만 다시 시도할 수 있게 둔다(호출은 {@code ChatQueueScheduler.match}).
 *
 * <h2>상담원이 없으면 티켓을 만들지 않는다</h2>
 * {@code autoAssign} 은 후보가 없으면 팀장에게 UNASSIGNED 알림을 보낸다. 대기열에 줄만 서도
 * 10초마다 팀장 알림이 쌓이지 않도록 후보 유무를 먼저 보고, 없으면 티켓 없이 WAITING 으로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatService {

    /** PRD Q16 — 대기 시간 초과 기준 */
    static final Duration WAIT_LIMIT = Duration.ofMinutes(5);

    private static final Set<ChatRoomStatus> ACTIVE = EnumSet.of(ChatRoomStatus.WAITING, ChatRoomStatus.OPEN);

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final TicketService ticketService;
    private final AssignmentService assignmentService;
    private final MemberQueryPort memberQueryPort;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 채팅 요청. 진행 중(WAITING·OPEN) 방이 있으면 새로 만들지 않고 그 방을 돌려준다 —
     * 새로고침·재요청마다 방이 생기면 한 고객이 대기열 여러 칸을 차지한다.
     *
     * <p>ponytail: 같은 고객의 동시 요청 두 건은 방 2개를 만들 수 있다. 화면이 버튼을 막으므로
     * 두지 않았고, 필요하면 chat_room(customer_id) WHERE status IN ('WAITING','OPEN') 부분 유니크 인덱스.
     */
    @Transactional
    public Long openOrGetRoom(Long customerId) {
        return roomRepository.findFirstByCustomerIdAndStatusIn(customerId, ACTIVE)
                .orElseGet(() -> roomRepository.save(ChatRoom.waiting(customerId, OffsetDateTime.now())))
                .getId();
    }

    /**
     * WAITING 방을 상담원과 연결한다. 연결했으면 true, 상담원이 없거나 이미 다른 상태면 false.
     *
     * @throws BusinessException 확인 직후 상담원이 사라진 경합(ASSIGN_NO_AVAILABLE_AGENT) — 이 트랜잭션은
     *                           롤백되고 방은 WAITING 으로 남는다
     */
    @Transactional
    public boolean tryMatch(Long roomId) {
        ChatRoom room = lockRoom(roomId);
        if (room.getStatus() != ChatRoomStatus.WAITING || memberQueryPort.findAssignableAgents().isEmpty()) {
            return false;
        }

        Ticket ticket = ticketService.createChatTicket(room.getCustomerId(), waitingContent(roomId));
        Long agentId = assignmentService.autoAssign(ticket.getId());
        if (agentId == null) {
            throw new BusinessException(AssignErrorCode.NO_AVAILABLE_AGENT);
        }

        room.open(agentId, ticket.getId(), OffsetDateTime.now());
        push(room.getCustomerId(), new ChatStatusPayload(room.getId(), ChatRoomStatus.OPEN.name(), null,
                room.getQueuedAt(), memberQueryPort.getMember(agentId).name()));
        log.info("[chat] 연결 roomId={} ticketNo={} agentId={}", roomId, ticket.getTicketNo(), agentId);
        return true;
    }

    /** 고객 본인 방의 현재 상태 (POST /api/chat/rooms 응답) */
    public ChatStatusPayload status(Long roomId, Long customerId) {
        ChatRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_FOUND));
        requireCustomer(room, customerId);
        return toPayload(room, waitingIds(), OffsetDateTime.now());
    }

    /**
     * "문의로 남기기" (FR-CHT-05). 대기 중 메시지를 본문으로 일반 문의를 만든다 — 배정하지 않고
     * RECEIVED 로 두면 분류 리스너가 웹 문의와 같은 경로로 배정한다.
     * 5분 경과는 화면 타이머를 믿지 않고 서버 시각으로 다시 확인한다.
     */
    @Transactional
    public ChatConvertResponse convert(Long roomId, Long customerId) {
        ChatRoom room = lockRoom(roomId);
        requireCustomer(room, customerId);
        if (room.getStatus() != ChatRoomStatus.WAITING) {
            throw new BusinessException(ChatErrorCode.INVALID_STATE);
        }
        if (!timedOut(room, OffsetDateTime.now())) {
            throw new BusinessException(ChatErrorCode.WAIT_NOT_EXPIRED);
        }
        String content = waitingContent(roomId);
        if (content.isEmpty()) {
            throw new BusinessException(ChatErrorCode.EMPTY_MESSAGES);
        }

        Ticket ticket = ticketService.createChatTicket(customerId, content);
        room.convert(ticket.getId(), OffsetDateTime.now());
        log.info("[chat] 문의 전환 roomId={} ticketNo={}", roomId, ticket.getTicketNo());
        return new ChatConvertResponse(ticket.getTicketNo());
    }

    /** 대기 중 나가기 (FR-CHT-05). 티켓은 만들지 않는다 */
    @Transactional
    public void cancel(Long roomId, Long customerId) {
        ChatRoom room = lockRoom(roomId);
        requireCustomer(room, customerId);
        room.cancel(OffsetDateTime.now());
        log.info("[chat] 대기 취소 roomId={}", roomId);
    }

    /** 대기 중인 방 id (FIFO). 스케줄러가 이 순서로 연결을 시도한다 */
    public List<Long> waitingIds() {
        return roomRepository.findByStatusOrderByQueuedAtAscIdAsc(ChatRoomStatus.WAITING).stream()
                .map(ChatRoom::getId)
                .toList();
    }

    /** 남은 대기자 전원에게 순번·시간 초과 여부를 다시 보낸다 (FR-CHT-04, /user/queue/chat-status) */
    public void pushWaitingStatuses() {
        List<ChatRoom> waiting = roomRepository.findByStatusOrderByQueuedAtAscIdAsc(ChatRoomStatus.WAITING);
        List<Long> ids = waiting.stream().map(ChatRoom::getId).toList();
        OffsetDateTime now = OffsetDateTime.now();
        waiting.forEach(room -> push(room.getCustomerId(), toPayload(room, ids, now)));
    }

    private ChatStatusPayload toPayload(ChatRoom room, List<Long> waitingIds, OffsetDateTime now) {
        String agentName = room.getAgentId() != null ? memberQueryPort.getMember(room.getAgentId()).name() : null;
        if (room.getStatus() != ChatRoomStatus.WAITING) {
            return new ChatStatusPayload(room.getId(), room.getStatus().name(), null, room.getQueuedAt(), agentName);
        }
        String status = timedOut(room, now) ? "TIMEOUT" : ChatRoomStatus.WAITING.name();
        return new ChatStatusPayload(room.getId(), status, waitingIds.indexOf(room.getId()) + 1,
                room.getQueuedAt(), null);
    }

    private static boolean timedOut(ChatRoom room, OffsetDateTime now) {
        return !room.getQueuedAt().plus(WAIT_LIMIT).isAfter(now);
    }

    /** 대기 중 고객이 보낸 메시지를 시간순으로 이어 붙인다 — 티켓 본문이 된다(docs/04 §10) */
    private String waitingContent(Long roomId) {
        return messageRepository.findByRoomIdOrderByIdAsc(roomId).stream()
                .map(ChatMessage::getContent)
                .collect(Collectors.joining("\n"));
    }

    private ChatRoom lockRoom(Long roomId) {
        return roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.NOT_FOUND));
    }

    /** 요청·전환·나가기는 고객 본인만 한다. 상담원은 참여자여도 이 경로를 쓰지 않는다 */
    private static void requireCustomer(ChatRoom room, Long customerId) {
        if (!room.getCustomerId().equals(customerId)) {
            throw new BusinessException(ChatErrorCode.NOT_PARTICIPANT);
        }
    }

    /**
     * 커밋 후 발송. {@code NotificationAdapter.push} 와 같은 이유 — 롤백된 연결을 고객이 OPEN 으로
     * 받으면 존재하지 않는 대화방으로 들어간다. 발송 실패는 다음 스케줄러 주기가 다시 보낸다.
     */
    private void push(Long customerId, ChatStatusPayload payload) {
        Runnable send = () -> {
            try {
                messagingTemplate.convertAndSendToUser(String.valueOf(customerId), "/queue/chat-status", payload);
            } catch (RuntimeException e) {
                log.warn("[chat] 상태 발송 실패 roomId={} cause={}", payload.roomId(), e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
            return;
        }
        send.run();
    }
}
