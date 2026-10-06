// @owner PMJ
package com.helpnest.domain.ticket.port;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.helpnest.domain.ticket.entity.Ticket;
import com.helpnest.domain.ticket.entity.TicketCategory;
import com.helpnest.domain.ticket.entity.TicketReply;
import com.helpnest.domain.ticket.entity.WriterType;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.domain.ticket.repository.TicketReplyRepository;
import com.helpnest.domain.ticket.repository.TicketRepository;
import com.helpnest.domain.ticket.service.TicketClassificationService;
import com.helpnest.global.error.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 신수진의 AI 분류·초안 생성에 티켓 데이터를 넘기는 포트 구현 (docs/02 §5.2, docs/05 §3·§4).
 *
 * <h2>본문을 마스킹하지 않는다</h2>
 * {@link TicketSummary} 주석대로 개인정보 마스킹은 프롬프트를 조립하는 호출자 책임이다.
 * 포트가 미리 가공하면 호출자가 원문을 되찾을 수 없고, LLM 전송용과 화면 표시용 마스킹 규칙이
 * 달라질 때 대응할 수 없다.
 *
 * <h2>로그에 티켓 본문을 남기지 않는다</h2>
 * 두 메서드 모두 본문·제목을 다루지만 로그에는 식별자와 건수만 남긴다(docs/10 §3.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketQueryAdapter implements TicketQueryPort {

    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;

    /**
     * 분류 프롬프트 입력 (docs/05 §3.1 2번).
     *
     * <p>없는 티켓에 null 을 돌려주지 않고 {@code TICKET_NOT_FOUND} 를 던진다 — 비동기 분류
     * 리스너가 null 을 받으면 "분류할 내용이 없다"와 "티켓이 사라졌다"를 구분할 수 없고,
     * 조용히 넘어가면 분류 결과가 영구히 비게 된다.
     */
    @Override
    public TicketSummary getTicketSummary(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));
        return new TicketSummary(ticket.getTitle(), ticket.getContent(),
                ticket.getCategory().name());
    }

    /**
     * 답변 초안의 참고 자료가 되는 과거 답변 (docs/05 §4.1).
     *
     * <p>유형 문자열은 {@code TicketClassificationService.parseEnum} 으로 변환한다 — LLM·호출자가
     * 넘기는 값이 소문자이거나 공백이 섞일 수 있고, 목록 밖 값이면
     * {@code TICKET_INVALID_CLASSIFICATION} 으로 거부한다. 분류 반영 경로와 같은 규약을 쓴다.
     *
     * <p>유형이 null·빈 문자열이면 빈 목록을 돌려준다. 유형 없이 전체에서 고르면 엉뚱한 분야의
     * 답변이 초안에 섞이므로, 참고 자료가 없는 것이 잘못된 참고 자료보다 낫다.
     *
     * @param limit 1 미만이면 빈 목록
     */
    @Override
    public List<ResolvedReply> findResolvedReplies(String category, int limit) {
        TicketCategory parsed = TicketClassificationService.parseEnum(TicketCategory.class, category,
                "category");
        if (parsed == null || limit < 1) {
            return List.of();
        }
        List<ResolvedReply> replies = ticketReplyRepository.findResolvedReplies(parsed,
                PageRequest.of(0, limit));
        log.debug("[query] 과거 답변 category={} limit={} found={}", parsed, limit, replies.size());
        return replies;
    }

    /**
     * 해결 결과 메일 입력 (CR #48).
     *
     * <p>두 번 조회한다. 티켓과 답변을 한 쿼리로 조인하면 공개 답변이 없는 티켓이 결과에서
     * 사라져 "티켓이 없다"와 "답변이 없다"를 구분할 수 없게 된다. 전자는 예외, 후자는
     * {@code lastPublicReply == null} 로 구분되어야 한다 — 답변 없이 해결된 티켓도 결과 메일은
     * 가야 한다.
     */
    @Override
    public ResolvedMailInfo getResolvedMailInfo(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(TicketErrorCode.NOT_FOUND));

        String lastPublicReply = ticketReplyRepository
                .findFirstByTicketIdAndWriterTypeAndIsInternalFalseOrderByCreatedAtDescIdDesc(
                        ticketId, WriterType.AGENT)
                .map(TicketReply::getContent)
                .orElse(null);

        // 티켓번호와 답변 유무만 남긴다 — 제목·본문·비회원 이메일은 로그 금지 (docs/10 §3.3)
        log.debug("[query] 해결 메일 정보 ticketNo={} member={} hasPublicReply={}",
                ticket.getTicketNo(), ticket.getCustomerId() != null, lastPublicReply != null);

        return new ResolvedMailInfo(ticket.getTicketNo(), ticket.getTitle(), ticket.getCustomerId(),
                ticket.getGuestName(), ticket.getGuestEmail(), lastPublicReply);
    }
}
