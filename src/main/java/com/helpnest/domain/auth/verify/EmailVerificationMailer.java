// @owner SSJ
package com.helpnest.domain.auth.verify;

import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.event.MemberSignedUpEvent;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.infra.mail.EmailVerificationMailCommand;
import com.helpnest.infra.mail.MailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 인증 메일 발송: 가입 직후(이벤트)와 재발송 요청. 둘 다 비동기라 응답 시간으로 가입 여부가 드러나지 않는다.
 * 전송 실패는 MailSender 가 MAIL_LOG FAILED 로 남기고 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class EmailVerificationMailer {

    private final EmailVerificationService verificationService;
    private final MemberRepository memberRepository;
    private final MailSender mailSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(MemberSignedUpEvent event) {
        send(event.memberId(), event.email(), event.name());
    }

    /** 인증 전(PENDING) 회원일 때만 보낸다. 없는 이메일·이미 인증한 회원은 조용히 무시 */
    @Async
    public void resend(String email) {
        memberRepository.findByEmail(email.trim())
                .filter(m -> m.getStatus() == MemberStatus.PENDING)
                .ifPresent(m -> send(m.getId(), m.getEmail(), m.getName()));
    }

    // issueLink 가 커밋된 뒤 메일을 보낸다
    private void send(Long memberId, String email, String name) {
        String verifyUrl = verificationService.issueLink(memberId);
        mailSender.sendEmailVerificationMail(new EmailVerificationMailCommand(email, name, verifyUrl));
    }
}
