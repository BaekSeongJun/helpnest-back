// @owner PMJ
package com.helpnest.domain.chat.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * 웹소켓 발송을 커밋 뒤로 미룬다 ({@code NotificationAdapter.push} 와 같은 이유). 커밋 전에 보내면
 * 롤백된 연결·메시지를 화면이 먼저 받는다. 트랜잭션 밖이면 바로 보낸다.
 *
 * <p>발송 실패는 삼킨다 — 커밋은 이미 끝났고, 상태·메시지는 재조회로 복구된다.
 */
@Slf4j
final class AfterCommit {

    private AfterCommit() {
    }

    static void send(Runnable send) {
        Runnable safe = () -> {
            try {
                send.run();
            } catch (RuntimeException e) {
                log.warn("[chat] 웹소켓 발송 실패 — 재조회로 복구된다 cause={}", e.toString());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safe.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safe.run();
            }
        });
    }
}
