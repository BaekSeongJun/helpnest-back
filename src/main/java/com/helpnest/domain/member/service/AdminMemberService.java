// @owner BSJ
package com.helpnest.domain.member.service;

import com.helpnest.domain.auth.repository.RefreshTokenRepository;
import com.helpnest.domain.member.dto.AdminMemberCreateRequest;
import com.helpnest.domain.member.dto.AdminMemberResponse;
import com.helpnest.domain.member.dto.AdminMemberUpdateRequest;
import com.helpnest.domain.member.entity.Member;
import com.helpnest.domain.member.entity.MemberRole;
import com.helpnest.domain.member.entity.MemberStatus;
import com.helpnest.domain.member.error.MemberErrorCode;
import com.helpnest.domain.member.repository.MemberRepository;
import com.helpnest.global.common.PageResponse;
import com.helpnest.global.error.BusinessException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 관리자 계정 관리 (AD-01, docs/04 §2). 권한(ADMIN)은 컨트롤러에서 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminMemberService {

    /** 고객은 직접 가입만, 역할 변경도 직원 역할 사이에서만 (고객 ↔ 직원 전환 금지) */
    private static final Set<MemberRole> CREATABLE_ROLES = Set.of(MemberRole.AGENT, MemberRole.LEAD);
    private static final Set<MemberRole> STAFF_ROLES = Set.of(MemberRole.AGENT, MemberRole.LEAD, MemberRole.ADMIN);

    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public PageResponse<AdminMemberResponse> list(MemberRole role, MemberStatus status, Pageable pageable) {
        return PageResponse.from(memberRepository.search(role, status, pageable).map(AdminMemberResponse::from));
    }

    @Transactional
    public AdminMemberResponse create(AdminMemberCreateRequest req) {
        if (!CREATABLE_ROLES.contains(req.role())) {
            throw new BusinessException(MemberErrorCode.ROLE_NOT_ALLOWED);
        }
        if (memberRepository.existsByEmail(req.email())) {
            throw new BusinessException(MemberErrorCode.EMAIL_DUPLICATED);
        }
        Member member = Member.builder()
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .phone(StringUtils.hasText(req.phone()) ? req.phone() : null)
                .role(req.role())
                .build();
        return AdminMemberResponse.from(memberRepository.save(member));
    }

    /**
     * 역할·상태가 바뀌면 Refresh 를 전부 폐기해 다음 재발급부터 새 권한(또는 차단)이 적용되게 한다.
     * ponytail: 이미 발급된 Access 토큰은 만료(30분)까지 유효 — 즉시 차단이 필요하면 토큰 버전 클레임 도입
     */
    @Transactional
    public AdminMemberResponse update(Long adminId, Long memberId, AdminMemberUpdateRequest req) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.NOT_FOUND));
        boolean roleChanged = req.role() != null && req.role() != member.getRole();
        boolean statusChanged = req.status() != null && req.status() != member.getStatus();

        // 본인 강등·비활성 금지 → ADMIN 이 0명이 되는 일을 막는다
        if (member.getId().equals(adminId) && (roleChanged || req.status() == MemberStatus.INACTIVE)) {
            throw new BusinessException(MemberErrorCode.SELF_CHANGE_FORBIDDEN);
        }
        if (roleChanged && !(STAFF_ROLES.contains(member.getRole()) && STAFF_ROLES.contains(req.role()))) {
            throw new BusinessException(MemberErrorCode.ROLE_NOT_ALLOWED);
        }

        if (roleChanged) {
            member.changeRole(req.role());
        }
        if (statusChanged) {
            member.changeStatus(req.status());
        }
        if (roleChanged || statusChanged) {
            refreshTokenRepository.revokeAllByMemberId(member.getId());
        }
        return AdminMemberResponse.from(member);
    }
}
