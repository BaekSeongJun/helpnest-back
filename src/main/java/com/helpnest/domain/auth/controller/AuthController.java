// @owner BSJ
package com.helpnest.domain.auth.controller;

import com.helpnest.domain.auth.dto.AuthResponse;
import com.helpnest.domain.auth.dto.GuestLoginRequest;
import com.helpnest.domain.auth.dto.GuestPasswordResetMailRequest;
import com.helpnest.domain.auth.dto.GuestPasswordResetRequest;
import com.helpnest.domain.auth.dto.GuestTokenResponse;
import com.helpnest.domain.auth.dto.LoginRequest;
import com.helpnest.domain.auth.dto.PasswordResetMailRequest;
import com.helpnest.domain.auth.dto.PasswordResetRequest;
import com.helpnest.domain.auth.dto.SignupRequest;
import com.helpnest.domain.auth.service.AuthService;
import com.helpnest.domain.auth.service.PasswordResetService;
import com.helpnest.domain.member.dto.MemberResponse;
import com.helpnest.global.common.ApiResponse;
import com.helpnest.global.security.JwtProperties;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    static final String REFRESH_COOKIE = "refreshToken";

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final JwtProperties jwtProperties;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<MemberResponse>> signup(@Valid @RequestBody SignupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(authService.signup(req)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest req) {
        return withRefreshCookie(authService.login(req));
    }

    /** 비회원 문의 조회 (CU-05). Refresh 쿠키 없이 Guest 토큰만 본문으로 */
    @PostMapping("/guest")
    public ApiResponse<GuestTokenResponse> guest(@Valid @RequestBody GuestLoginRequest req) {
        return ApiResponse.ok(authService.guestLogin(req));
    }

    /** 비밀번호 찾기 (CM-03). 가입 여부와 무관하게 항상 200 — 계정 존재 비노출 (FR-AUTH-07) */
    @PostMapping("/password/reset-request")
    public ApiResponse<Void> requestPasswordReset(@Valid @RequestBody PasswordResetMailRequest req) {
        passwordResetService.requestMemberReset(req.email());
        return ApiResponse.ok();
    }

    /** 재설정 (CM-04). 성공 시 모든 Refresh 폐기 → 새 비밀번호로 다시 로그인 */
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody PasswordResetRequest req) {
        passwordResetService.resetMemberPassword(req.token(), req.newPassword());
        return ApiResponse.ok();
    }

    /** 비회원 조회 비밀번호 재설정 메일 (CU-06 ①). 티켓번호·이메일이 맞든 아니든 항상 200 (FR-AUTH-09) */
    @PostMapping("/guest/reset-request")
    public ApiResponse<Void> requestGuestReset(@Valid @RequestBody GuestPasswordResetMailRequest req) {
        passwordResetService.requestGuestReset(req.ticketNo(), req.email());
        return ApiResponse.ok();
    }

    /** 새 조회 비밀번호 (CU-06 ②) */
    @PostMapping("/guest/reset")
    public ApiResponse<Void> resetGuestPassword(@Valid @RequestBody GuestPasswordResetRequest req) {
        passwordResetService.resetGuestPassword(req.token(), req.newPassword());
        return ApiResponse.ok();
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withRefreshCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .body(ApiResponse.ok());
    }

    private ResponseEntity<ApiResponse<AuthResponse>> withRefreshCookie(AuthService.Tokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), jwtProperties.refreshTtl()).toString())
                .body(ApiResponse.ok(tokens.body()));
    }

    /** httpOnly·SameSite=Lax·Path=/ (04 §2). Secure 는 로컬 http 개발만 끔 (REFRESH_COOKIE_SECURE) */
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(jwtProperties.refreshCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
