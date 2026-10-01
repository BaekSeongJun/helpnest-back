// @owner PMJ
package com.helpnest.domain.ticket.repository;

/**
 * 상담원 1명의 현재 부하 (PRD 6.2 "최소 부하 배정").
 *
 * @param agentId     상담원 member_id
 * @param activeCount 처리 중인 티켓 수 (ASSIGNED + IN_PROGRESS). RESOLVED·CLOSED 는 세지 않는다 —
 *                    이미 손을 뗀 티켓이 새 배정을 막으면 안 된다.
 */
public record AgentLoad(Long agentId, long activeCount) {
}
