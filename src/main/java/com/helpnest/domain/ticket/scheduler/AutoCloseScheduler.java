// @owner PMJ
package com.helpnest.domain.ticket.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.helpnest.domain.ticket.entity.ActorRole;
import com.helpnest.domain.ticket.entity.TicketStatus;
import com.helpnest.domain.ticket.listener.TicketCloseListener;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.TicketService;

import lombok.extern.slf4j.Slf4j;

/**
 * 해결 후 설문 응답 없이 72시간이 지난 티켓을 자동 종료한다
 * (PRD FR-SRV-04, 전이표 "72시간 경과", docs/03 §4.9 대상 쿼리, docs/02 §20 10분 주기).
 *
 * <p>{@link TicketCloseListener} 가 설문 제출로 바로 종료하는 길이고, 이쪽은 <b>아무 응답이 없을
 * 때의 최종 보장</b>이다. 둘 다 {@code TicketService.changeStatus} 한 자리를 통과하므로 종료 경로가
 * 어떻든 이력·알림이 같게 남는다.
 *
 * <h2>스캔 전체를 한 트랜잭션으로 묶지 않는다 (SlaScheduler 와 다른 점)</h2>
 * {@code SlaScheduler} 는 플래그 두 개만 켜므로 한 트랜잭션이 맞다. 여기는 티켓마다 전이·이력·
 * 이벤트가 완결돼야 한다.
 * <ul>
 *   <li>한 건이 실패했을 때 이미 종료한 앞의 티켓까지 롤백되면 안 된다.</li>
 *   <li>{@code changeStatus} 가 발행하는 {@code TicketStatusChangedEvent} 의 구독자는
 *       {@code AFTER_COMMIT} 이다. 회차 전체를 묶으면 알림이 맨 끝에 한꺼번에 나가고, 중간에
 *       터지면 앞서 보낸 것과 DB 상태가 어긋난다.</li>
 * </ul>
 * 그래서 이 메서드에는 {@code @Transactional} 이 없고, 트랜잭션 경계는 {@code changeStatus} 호출
 * 하나하나다. 대상 조회는 읽기뿐이라 경계 밖에서 돌아도 된다.
 *
 * <h2>티켓별 try-catch 는 방어가 아니라 정상 경로다</h2>
 * 대상을 조회한 뒤 전이하기까지의 사이에 고객이 추가 답글을 달면 상태가 IN_PROGRESS 로 바뀌어
 * 있고(재문의), SYSTEM 의 IN_PROGRESS→CLOSED 는 전이표에 없으므로 {@code changeStatus} 가
 * {@code TICKET_INVALID_TRANSITION} 을 던진다. 잡지 않으면 그 한 건 때문에 뒤의 티켓이 이 회차에
 * 처리되지 않고, 다음 회차에도 같은 순서로 같은 자리에서 멈춘다.
 */
@Slf4j
@Component
public class AutoCloseScheduler {

    /**
     * 해결 후 이만큼 지나면 설문 미응답으로 보고 종료한다 (FR-SRV-04).
     *
     * <p>설문 링크 유효 기간(docs/04 §2 "발송 후 72시간")과 같은 값이다. 설정으로 빼지 않는 이유는
     * 둘이 같아야 의미가 있기 때문이다 — 한쪽만 늘리면 링크는 살아 있는데 티켓이 이미 종료돼
     * 제출할 수 없거나, 종료되지 않은 티켓에 만료된 링크가 남는다.
     */
    private static final Duration AUTO_CLOSE_AFTER = Duration.ofHours(72);

    private static final String MEMO = "해결 후 72시간 경과 자동 종료";

    private final TicketRepository ticketRepository;
    private final TicketService ticketService;
    private final Clock clock;

    @Autowired
    public AutoCloseScheduler(TicketRepository ticketRepository, TicketService ticketService) {
        this(ticketRepository, ticketService, Clock.systemUTC());
    }

    /** 테스트가 시각을 고정하려고 쓰는 생성자 ({@code SlaScheduler} 와 같은 방식) */
    AutoCloseScheduler(TicketRepository ticketRepository, TicketService ticketService, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.ticketService = ticketService;
        this.clock = clock;
    }

    // ponytail: fixedDelay 라 단일 인스턴스에서만 중복이 없다 — 다중 인스턴스 배포 시 ShedLock 등
    //           분산 락 (SlaScheduler·MailRetryScheduler 와 같은 한계)
    @Scheduled(fixedDelayString = "${app.ticket.auto-close-delay:10m}",
            initialDelayString = "${app.ticket.auto-close-delay:10m}")
    public void scan() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(AUTO_CLOSE_AFTER);
        // ponytail: 한 회차에 전부 가져온다 — 스케줄러가 오래 멈췄다 재기동하면 밀린 만큼 한 번에
        //           읽는다. 건수가 문제가 되면 MailRetryScheduler 처럼 상한을 둔다
        List<Long> targets = ticketRepository.findAutoCloseTargets(cutoff);
        for (Long ticketId : targets) {
            close(ticketId);
        }
        if (!targets.isEmpty()) {
            log.info("[ticket] 자동 종료 대상 {}건 cutoff={}", targets.size(), cutoff);
        }
    }

    private void close(Long ticketId) {
        try {
            ticketService.changeStatus(ticketId, TicketStatus.CLOSED, MEMO, null, ActorRole.SYSTEM);
        } catch (Exception e) {
            // 재문의로 상태가 바뀐 경우가 대부분이다(클래스 주석) — 다음 티켓으로 넘어간다
            log.warn("[ticket] 자동 종료 건너뜀 ticketId={} cause={}", ticketId, e.toString());
        }
    }
}
