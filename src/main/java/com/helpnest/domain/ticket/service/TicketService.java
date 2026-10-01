// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.attachment.port.AttachmentPort;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.dto.TicketCreateRequest;
import com.helpnest.domain.ticket.dto.TicketCreateResponse;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import com.helpnest.domain.ticket.repository.TicketHistoryRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import com.helpnest.global.security.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 티켓 접수·조회·상태 변경 (docs/04 §7).
 *
 * <p>docs/02 §5.3 시퀀스에서 이 서비스가 맡는 첫 단계는 "저장(RECEIVED, 기본 NORMAL,
 * SLA due 계산) → 201 응답 → TicketCreatedEvent(after commit, async)" 다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    /** 비회원 접수 제한: 같은 이메일로 1시간 5건 (docs/04 §7) */
    private static final int GUEST_EMAIL_LIMIT = 5;
    private static final Duration GUEST_EMAIL_WINDOW = Duration.ofHours(1);

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final TicketNoGenerator ticketNoGenerator;
    private final AttachmentPort attachmentPort;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 문의를 접수한다.
     *
     * <p>티켓번호 발급·SLA 기한 계산·CREATE 이력·첨부 연결·이벤트 발행을 한 트랜잭션으로
     * 묶는다. 이벤트는 트랜잭션 <b>안에서</b> 발행하지만 구독자가
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 이므로 커밋 후에 전달된다
     * ({@link TicketCreatedEvent} 주석). 여기서 커밋 후 발행으로 바꾸면 접수가 롤백돼도
     * 분류가 돌아간다.
     *
     * @param customerId 로그인 회원의 member_id. 비회원이면 null
     * @return 201 응답 본문
     */
    @Transactional
    public TicketCreateResponse create(TicketCreateRequest req, Long customerId) {
        TicketCreateRequest.GuestInfo guest = resolveGuest(req, customerId);
        OffsetDateTime now = OffsetDateTime.now();

        Ticket ticket = ticketRepository.save(Ticket.builder()
                .ticketNo(ticketNoGenerator.generate(now))
                .customerId(customerId)
                .guestName(guest != null ? guest.name() : null)
                .guestEmail(guest != null ? guest.email() : null)
                .guestPasswordHash(guest != null ? passwordEncoder.encode(guest.password()) : null)
                .title(req.title())
                .content(req.content())
                .channel(TicketChannel.WEB)
                .category(req.categoryHint())
                .firstResponseDueAt(defaultPolicy().calculateDueAt(now))
                .build());

        ticketHistoryRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action(HistoryAction.CREATE)
                .toValue(ticket.getStatus().name())
                .actorId(customerId)
                .actorType(customerId != null ? ActorType.MEMBER : ActorType.GUEST)
                .build());

        linkAttachments(req.attachmentIds(), ticket.getId());

        eventPublisher.publishEvent(new TicketCreatedEvent(ticket.getId()));
        log.info("[ticket] 접수 ticketNo={} member={} guest={}", ticket.getTicketNo(), customerId,
                guest != null);
        return TicketCreateResponse.from(ticket);
    }

    /**
     * 회원·비회원 판별. 로그인했으면 본문의 guest 는 <b>무시한다</b> — 본문을 믿으면 로그인한
     * 사용자가 남의 이메일로 비회원 티켓을 만들어 그 사람의 조회 화면에 섞어 넣을 수 있다.
     * 비회원이면서 guest 정보가 없으면 접수할 주체가 없으므로 거절한다.
     *
     * <p>비회원 경로일 때만 이메일 기준 요청 제한을 적용한다. IP 기준 제한은 공용
     * {@code RateLimitFilter}(백성준)가 이미 거르고, 여기서는 같은 사람이 IP 를 바꿔 가며
     * 접수를 반복하는 경우를 막는다(docs/04 §7).
     */
    private TicketCreateRequest.GuestInfo resolveGuest(TicketCreateRequest req, Long customerId) {
        if (customerId != null) {
            return null;
        }
        TicketCreateRequest.GuestInfo guest = req.guest();
        if (guest == null) {
            throw new BusinessException(TicketErrorCode.GUEST_INFO_REQUIRED);
        }
        String key = "ticket-email:" + guest.email().toLowerCase();
        if (!rateLimiter.tryAcquire(key, GUEST_EMAIL_LIMIT, GUEST_EMAIL_WINDOW)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        return guest;
    }

    /**
     * 접수 시 적용할 기본 SLA 정책. 분류 전에는 우선순위가 NORMAL 이므로 NORMAL 정책을 쓰고,
     * AI 분류로 우선순위가 바뀌면 기한을 다시 계산한다(PRD 6.1).
     *
     * <p>sla_policy 4행은 마이그레이션이 넣으므로 없으면 스키마가 깨진 것이다. 사용자 입력
     * 문제가 아니라 배포 문제이므로 BusinessException 이 아닌 IllegalStateException 을 던져
     * 500 으로 보낸다.
     */
    private SlaPolicy defaultPolicy() {
        return slaPolicyRepository.findById(TicketPriority.NORMAL)
                .orElseThrow(() -> new IllegalStateException(
                        "sla_policy 에 NORMAL 정책이 없습니다. 마이그레이션 적용 상태를 확인해 주세요."));
    }

    /**
     * 업로드만 돼 있던 첨부를 이 티켓에 연결한다. 첨부 소유권 검증(남의 첨부를 가로채는지,
     * 이미 연결된 첨부인지)은 포트 구현의 책임이다(docs/04 §3 {@code ATTACHMENT_LINK_DENIED}).
     */
    private void linkAttachments(List<Long> attachmentIds, Long ticketId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        attachmentPort.linkToTicket(attachmentIds, ticketId, null);
    }
}
