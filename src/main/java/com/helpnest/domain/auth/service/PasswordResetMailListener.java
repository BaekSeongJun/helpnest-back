// @owner BSJ
package com.helpnest.domain.auth.service;

import com.helpnest.infra.mail.MailSender;
import com.helpnest.infra.mail.PasswordResetMailCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 재설정 메일을 토큰 커밋 후 비동기로 보낸다. 커밋 전에 보내면 롤백 시 죽은 링크가 나가고,
 * 동기로 보내면 메일 전송 시간만큼 "가입된 이메일"의 응답이 느려져 계정 존재가 드러난다.
 * 전송 실패는 MailSender 가 MAIL_LOG FAILED 로 남기고 재시도한다(신수진).
 */
@Component
@RequiredArgsConstructor
public class PasswordResetMailListener {

    private final MailSender mailSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PasswordResetMailCommand command) {
        mailSender.sendPasswordResetMail(command);
    }
}
