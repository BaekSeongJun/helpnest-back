// @owner BSJ
package com.helpnest.domain.member.entity;

import com.helpnest.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /** BCrypt 해시 */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status;

    /** 상담원 자동 배정 대상 여부 */
    @Column(nullable = false)
    private boolean available;

    /** 최소 부하 동률 시 먼저 배정할 상담원 판단용 */
    private OffsetDateTime lastAssignedAt;

    @Builder
    private Member(String email, String password, String name, String phone, MemberRole role) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.role = role;
        this.status = MemberStatus.ACTIVE;
        this.available = false;
    }

    public void touchLastAssigned(OffsetDateTime at) {
        this.lastAssignedAt = at;
    }

    /** 상담원이 아니게 되면 자동 배정 대상에서도 빠진다 */
    public void changeRole(MemberRole role) {
        this.role = role;
        if (role != MemberRole.AGENT) {
            this.available = false;
        }
    }

    public void changeStatus(MemberStatus status) {
        this.status = status;
    }

    public void changeAvailable(boolean available) {
        this.available = available;
    }

    /** @param passwordHash BCrypt 해시. Refresh 폐기는 호출자가 함께 한다 */
    public void changePassword(String passwordHash) {
        this.password = passwordHash;
    }

    /** 내 정보 수정 (CU-10). 이메일·역할은 바꾸지 않는다 */
    public void changeProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }
}
