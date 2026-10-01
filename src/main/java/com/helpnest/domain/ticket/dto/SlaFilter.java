// @owner PMJ
package com.helpnest.domain.ticket.dto;

/**
 * 콘솔 목록의 SLA 필터 값 (docs/04 §7 {@code ?sla=WARNING|BREACHED}).
 *
 * <p>DB 컬럼용 enum 이 아니다 — 티켓에 저장되는 것은 {@code sla_warned}·{@code sla_breached}
 * 두 불리언이고, 이 타입은 "지금 기준으로 임박인지 위반인지"를 묻는 조회 조건이다.
 */
public enum SlaFilter {

    /** 아직 응답하지 않았고 임박 시각은 지났지만 기한은 남은 상태 */
    WARNING,

    /** 기한을 넘긴 상태. 이미 위반으로 기록된 티켓도 포함한다 */
    BREACHED
}
