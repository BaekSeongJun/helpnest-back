// @owner PMJ
package com.helpnest.domain.ticket.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.helpnest.domain.assignment.service.AssignmentService;
import com.helpnest.domain.ticket.dto.ClassificationUpdateRequest;
import com.helpnest.domain.ticket.dto.TicketAssignRequest;
import com.helpnest.domain.ticket.service.TicketClassificationService;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProvider;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 상담 콘솔 티켓 API (docs/04 §7, 화면 CS-01·CS-02).
 *
 * <p>경로 전체가 SecurityConfig 에서 {@code hasRole("AGENT")} 로 묶여 있고 역할 계층이
 * {@code ADMIN > LEAD > AGENT} 이므로, 메서드에 추가 제약이 없으면 상담원 이상이 호출할 수 있다.
 *
 * <h2>배정 API 를 LEAD+ 로 좁힌 근거</h2>
 * {@code TicketStateMachine} 전이표(PRD 5장)는 재배정 수행자에 AGENT 를 포함하지만, PRD 2.1
 * 권한 매트릭스와 FR-ASN-02 는 수동 배정·재배정을 LEAD/ADMIN 전용으로 규정한다. 문서 간 충돌을
 * <b>전이표를 고치지 않고 API 권한으로 좁혀</b> 해결했다 — 전이표는 "상태 전이가 가능한가"의
 * 권위 문서이고, "누가 그 동작을 호출할 수 있는가"는 API 계층의 책임이기 때문이다. 전이표를
 * 고치면 채팅 배정(S3)처럼 시스템이 수행하는 전이까지 영향을 받는다.
 */
@RestController
@RequestMapping("/api/console/tickets")
@RequiredArgsConstructor
public class ConsoleTicketController {

    private final AssignmentService assignmentService;
    private final TicketClassificationService classificationService;

    /** 수동 배정·재배정 (FR-ASN-02). 담당자가 이미 있으면 REASSIGN 이력으로 남는다 */
    @PatchMapping("/{ticketId}/assign")
    @PreAuthorize("hasRole('LEAD')")
    public ResponseEntity<ApiResponse<Void>> assign(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketAssignRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        assignmentService.assignTo(ticketId, req.agentId(), req.memo(), JwtProvider.memberId(jwt));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * 자동 배정 재시도 (PRD 6.2). 상담원이 모두 OFF 라 미배정으로 남은 티켓을 팀장이 다시
     * 돌릴 때 쓴다. 가용 상담원이 여전히 없으면 RECEIVED 를 유지하고 200 을 돌려준다 —
     * 재시도 자체는 정상 처리됐고 "아직 아무도 없다"는 상태는 오류가 아니다.
     */
    @PostMapping("/{ticketId}/assign/auto")
    @PreAuthorize("hasRole('LEAD')")
    public ResponseEntity<ApiResponse<Void>> autoAssign(@PathVariable Long ticketId) {
        assignmentService.autoAssign(ticketId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /**
     * 분류 수동 수정 (docs/04 §7). 담당 AGENT 또는 LEAD+ 만 — 담당자 검증은 서비스가 한다
     * (경로 권한만으로는 "남의 티켓인지"를 알 수 없다).
     */
    @PatchMapping("/{ticketId}/classification")
    public ResponseEntity<ApiResponse<Void>> updateClassification(
            @PathVariable Long ticketId,
            @Valid @RequestBody ClassificationUpdateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        classificationService.applyManualUpdate(ticketId, req.category(), req.priority(),
                JwtProvider.memberId(jwt), isLeadOrAbove(jwt));
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /** 역할 계층(ADMIN > LEAD)은 Security 가 쓰고, 서비스 분기용으로는 클레임을 직접 본다 */
    private static boolean isLeadOrAbove(Jwt jwt) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        return "LEAD".equals(role) || "ADMIN".equals(role);
    }
}
