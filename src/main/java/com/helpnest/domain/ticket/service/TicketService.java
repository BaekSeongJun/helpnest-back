// @owner PMJ
package com.helpnest.domain.ticket.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.attachment.port.AttachmentPort;
import com.helpnest.domain.sla.entity.SlaPolicy;
import com.helpnest.domain.sla.repository.SlaPolicyRepository;
import com.helpnest.domain.ticket.dto.TicketCreateRequest;
import com.helpnest.domain.ticket.dto.TicketCreateResponse;
import com.helpnest.domain.ticket.dto.TicketHistoryResponse;
import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.ActorType;
import com.helpnest.domain.ticket.entity.HistoryAction;
import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketChannel;
import com.helpnest.domain.ticket.entity.TicketHistory;
import com.helpnest.domain.ticket.entity.TicketPriority;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.event.TicketCreatedEvent;
import com.helpnest.domain.ticket.event.TicketStatusChangedEvent;
import com.helpnest.domain.ticket.repository.MemberName;
import com.helpnest.domain.ticket.repository.MemberNameLookupRepository;
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
    private final MemberNameLookupRepository memberNameLookupRepository;
    private final SlaPolicyRepository slaPolicyRepository;
    private final TicketNoGenerator ticketNoGenerator;
    private final TicketStateMachine stateMachine;
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

    /**
     * 상태를 전이한다 (PATCH /api/console/tickets/{id}/status, FR-TKT-03).
     *
     * <p>{@code TicketStateMachine} 은 판정만 하므로 예외·이력·이벤트로 조립하는 것이 이 메서드의
     * 일이다({@link TicketStateMachine} 클래스 주석의 책임 경계). 네 가지가 항상 함께 일어난다 —
     * 상태 변경, STATUS_CHANGE 이력, 이벤트 발행, 그리고 그 앞의 권한 검증이다.
     *
     * <h2>담당자 검증을 여기서 하는 이유</h2>
     * 상태머신은 티켓을 인자로 받지 않아 "이 상담원이 그 티켓의 담당인지"를 알 수 없다. 역할만
     * 보고 통과시키면 AGENT 가 남의 티켓을 해결 처리할 수 있으므로(권한 우회) 서비스가 반드시
     * 추가한다. LEAD·ADMIN 은 전체 티켓 권한이 있어 담당 여부를 보지 않는다(PRD 2.1).
     *
     * <h2>toStatus=ASSIGNED 를 받지 않는 이유</h2>
     * 전이표에는 →ASSIGNED 전이가 3건(RECEIVED·ASSIGNED·IN_PROGRESS 에서) 있지만 이 API 로는
     * 수행할 수 없다. 요청 본문이 {@code {toStatus, memo}} 뿐이라 <b>누구에게</b> 배정할지 담을
     * 자리가 없고, 담당자를 그대로 둔 채 상태만 ASSIGNED 로 되돌리면 ASSIGN·REASSIGN 이력 없이
     * STATUS_CHANGE 행만 남아 "누가 누구에게 넘겼는지"가 이력에서 사라진다. 배정 경로는
     * {@code AssignmentService.assignTo} 가 세 상태 모두에서 처리하므로 기능 공백도 없다
     * (docs/04 §7 PATCH /assign). 전이표 자체는 고치지 않는다 — 채팅 배정(S3)처럼 시스템이
     * 수행하는 →ASSIGNED 전이가 여전히 합법이어야 하기 때문이다.
     *
     * @param actorId   수행자의 member_id. 시스템 자동 전이(S2 72시간 자동 종료)면 null
     * @param actorRole 수행자 역할. 호출자가 JWT role 클레임에서 변환해 넘긴다
     * @throws BusinessException TICKET_NOT_FOUND(404), TICKET_NOT_ASSIGNEE(403),
     *                           TICKET_INVALID_TRANSITION(409)
     */
    @Transactional
    public void changeStatus(Long ticketId, TicketStatus toStatus, String memo, Long actorId,
            ActorRole actorRole) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));

        if (actorRole == ActorRole.AGENT && !Objects.equals(ticket.getAgentId(), actorId)) {
            throw new BusinessException(TicketErrorCode.NOT_ASSIGNEE);
        }
        if (toStatus == TicketStatus.ASSIGNED) {
            throw new BusinessException(TicketErrorCode.INVALID_TRANSITION,
                    "담당자 변경은 배정 API 로 처리해 주세요.");
        }

        TicketStatus from = ticket.getStatus();
        if (!stateMachine.isAllowedFor(from, toStatus, actorRole)) {
            // 메시지 형식은 docs/04 §1.1 실패 응답 예시를 그대로 따른다
            throw new BusinessException(TicketErrorCode.INVALID_TRANSITION,
                    "%s에서 %s로 변경할 수 없습니다.".formatted(from, toStatus));
        }

        ticket.changeStatusTo(toStatus, OffsetDateTime.now());

        ticketHistoryRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action(HistoryAction.STATUS_CHANGE)
                .fromValue(from.name())
                .toValue(toStatus.name())
                .actorId(actorId)
                .actorType(actorId != null ? ActorType.MEMBER : ActorType.SYSTEM)
                .memo(memo)
                .build());

        eventPublisher.publishEvent(
                new TicketStatusChangedEvent(ticket.getId(), from, toStatus, actorId));
        log.info("[ticket] 상태 변경 ticketNo={} {} -> {} by={}", ticket.getTicketNo(), from,
                toStatus, actorId);
    }

    /**
     * 티켓 이력 목록 (GET /api/console/tickets/{id}/histories, CS-02 우측 패널).
     *
     * <p>이력이 없는 티켓은 있을 수 없지만(접수 시 CREATE 이력을 남긴다) 빈 목록을 404 로 바꾸지는
     * 않는다 — 티켓이 실제로 없는 경우와 구분해야 하므로 티켓 존재 여부를 따로 확인한다.
     */
    public List<TicketHistoryResponse> findHistories(Long ticketId) {
        if (!ticketRepository.existsById(ticketId)) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        List<TicketHistory> histories = ticketHistoryRepository
                .findByTicketIdOrderByCreatedAtAsc(ticketId);
        Map<Long, String> names = actorNames(histories);

        return histories.stream()
                .map(h -> new TicketHistoryResponse(h.getId(), h.getAction(), h.getFromValue(),
                        h.getToValue(), nameOf(names, h.getActorId()), h.getActorType(),
                        h.getMemo(), h.getCreatedAt()))
                .toList();
    }

    /**
     * 이력에 등장하는 수행자들의 이름을 한 번에 읽는다. 건당 포트 호출이면 N+1 이 되고,
     * 탈퇴 등으로 조회되지 않는 수행자가 있으면 이력 조회 전체가 실패한다
     * ({@link MemberNameLookupRepository} 주석).
     */
    private Map<Long, String> actorNames(List<TicketHistory> histories) {
        Set<Long> actorIds = histories.stream()
                .map(TicketHistory::getActorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return memberNameLookupRepository.findNames(actorIds).stream()
                .collect(Collectors.toMap(MemberName::getMemberId, MemberName::getName));
    }

    /**
     * SYSTEM·GUEST 수행자는 actorId 가 없으므로 이름도 null 이다.
     * {@code Map.of()} 는 null 키 조회에 NPE 를 던지므로 맵에 묻지 않고 먼저 거른다.
     */
    private static String nameOf(Map<Long, String> names, Long actorId) {
        return actorId == null ? null : names.get(actorId);
    }
}
