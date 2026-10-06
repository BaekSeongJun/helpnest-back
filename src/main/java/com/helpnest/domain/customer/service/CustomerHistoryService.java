// @owner BSJ
package com.helpnest.domain.customer.service;

import com.helpnest.domain.customer.dto.CustomerHistoryResponse;
import com.helpnest.domain.customer.dto.CustomerHistoryResponse.Item;
import com.helpnest.domain.customer.dto.CustomerHistoryResponse.Summary;
import com.helpnest.domain.customer.repository.CustomerHistoryRepository;
import com.helpnest.domain.customer.repository.CustomerHistoryRepository.OwnerRow;
import com.helpnest.domain.customer.repository.CustomerHistoryRepository.SummaryRow;
import com.helpnest.domain.ticket.error.TicketErrorCode;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 동일 고객 문의 이력 묶음 (FR-HIS-01·02). 회원은 ID, 비회원은 이메일(대소문자 무시)로 묶는다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomerHistoryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String MEMBER_PREFIX = "M-";
    private static final String GUEST_PREFIX = "G-";

    private final CustomerHistoryRepository repository;

    /** 고객 단위 이력 화면(CS-07). 목록에 모든 티켓이 들어간다 */
    public CustomerHistoryResponse byKey(String customerKey, Pageable pageable) {
        return load(customerKey, null, pageable);
    }

    /** 티켓 상세 패널(CS-02). 지금 보고 있는 티켓은 목록에서 빼고, 요약은 그 티켓까지 포함한다 */
    public CustomerHistoryResponse byTicket(Long ticketId, Pageable pageable) {
        OwnerRow owner = repository.findOwner(ticketId);
        if (owner == null) {
            throw new BusinessException(TicketErrorCode.NOT_FOUND);
        }
        String key = owner.getCustomerId() != null
                ? MEMBER_PREFIX + owner.getCustomerId()
                : GUEST_PREFIX + owner.getGuestEmail();
        return load(key, ticketId, pageable);
    }

    private CustomerHistoryResponse load(String customerKey, Long excludeTicketId, Pageable pageable) {
        Long customerId = null;
        String email = null;
        if (customerKey != null && customerKey.startsWith(MEMBER_PREFIX)) {
            customerId = parseMemberId(customerKey.substring(MEMBER_PREFIX.length()));
        } else if (customerKey != null && customerKey.startsWith(GUEST_PREFIX)
                && customerKey.length() > GUEST_PREFIX.length()) {
            // lower(guest_email) 함수 인덱스를 타려고 소문자로 맞춘다
            email = customerKey.substring(GUEST_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        }
        if (customerId == null && (email == null || email.isEmpty())) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "고객 키 형식이 올바르지 않습니다.");
        }

        SummaryRow s = repository.summarize(customerId, email);
        Summary summary = new Summary(s.getTotalCount(), s.getAvgRating() == null ? null : round1(s.getAvgRating()),
                s.getLastTicketAt() == null ? null : OffsetDateTime.ofInstant(s.getLastTicketAt(), SEOUL));
        PageResponse<Item> tickets = PageResponse
                .from(repository.findTickets(customerId, email, excludeTicketId, pageable).map(Item::from));
        return new CustomerHistoryResponse(customerKey, s.getCustomerName(), summary, tickets);
    }

    private static Long parseMemberId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // 아래에서 형식 오류로 처리
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
