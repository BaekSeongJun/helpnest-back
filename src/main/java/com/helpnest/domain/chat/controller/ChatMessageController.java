// @owner PMJ
package com.helpnest.domain.chat.controller;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import com.helpnest.domain.chat.dto.ChatSendRequest;
import com.helpnest.domain.chat.service.ChatMessageService;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 채팅 메시지 송신 (STOMP {@code /app/chat/{roomId}/send}, docs/04 §11).
 *
 * <p>Principal 이름은 CONNECT 에서 {@code StompAuthInterceptor} 가 넣은 member_id 다. 발신자를
 * 본문에서 받지 않는 이유 — 본문을 믿으면 남의 이름으로 메시지를 보낼 수 있다.
 */
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatMessageService;

    @MessageMapping("/chat/{roomId}/send")
    public void send(@DestinationVariable Long roomId, @Payload ChatSendRequest req, Principal principal) {
        chatMessageService.send(roomId, Long.valueOf(principal.getName()), req.content());
    }

    /**
     * STOMP 에는 HTTP 응답이 없어 거부 사유를 보낸 사람에게만 돌려준다({@code /user/queue/errors}).
     * {@code broadcast = false} — 같은 사용자의 다른 탭까지 오류를 띄우지 않는다.
     */
    @MessageExceptionHandler(BusinessException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public Map<String, String> handle(BusinessException e) {
        return Map.of("code", e.getErrorCode().code(), "message", e.getMessage());
    }
}
