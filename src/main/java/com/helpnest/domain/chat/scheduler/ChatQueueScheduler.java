// @owner PMJ
package com.helpnest.domain.chat.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.helpnest.domain.chat.service.ChatService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 채팅 대기열 처리 (FR-CHT-04, docs/02 §6 "10초 주기 + FIFO").
 *
 * <p>상담원 available ON·티켓 종료 시 즉시 재시도는 docs/02 §6 에 있지만, available 토글은
 * 백성준 {@code MemberService} 라 여기서 걸 수 없다. 최대 10초 늦게 연결되는 것으로 대신한다.
 * 채팅 종료 시 재시도는 채팅 종료 API(같은 도메인)에서 {@link #match} 를 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueScheduler {

    private final ChatService chatService;

    /**
     * 앞에서부터 연결하고, 한 명이라도 연결하지 못하면 멈춘다 — FIFO 라 앞사람이 안 되면 뒷사람도
     * 안 된다(상담원이 없다는 뜻). 그 뒤 남은 대기자 전원에게 순번을 다시 보낸다.
     *
     * <p>방마다 별도 트랜잭션이라 한 방의 실패가 다른 방을 되돌리지 않는다.
     */
    @Scheduled(fixedDelayString = "${app.chat.queue-delay:10s}", initialDelayString = "${app.chat.queue-delay:10s}")
    public void run() {
        for (Long roomId : chatService.waitingIds()) {
            if (!match(roomId)) {
                break;
            }
        }
        chatService.pushWaitingStatuses();
    }

    /**
     * 연결을 한 번 시도한다. 실패(경합으로 상담원이 사라짐 등)는 삼키고 false — 방은 WAITING 으로
     * 남아 다음 주기에 다시 시도된다. 채팅 요청 API 도 이 메서드로 즉시 1회 시도한다.
     */
    public boolean match(Long roomId) {
        try {
            return chatService.tryMatch(roomId);
        } catch (RuntimeException e) {
            log.info("[chat] 연결 보류 roomId={} cause={}", roomId, e.toString());
            return false;
        }
    }
}
