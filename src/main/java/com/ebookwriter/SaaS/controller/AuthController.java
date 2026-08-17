package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.UserDTO;
import com.ebookwriter.SaaS.entity.RefreshToken;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.request.EmailVerificationRequest;
import com.ebookwriter.SaaS.request.LoginRequest;
import com.ebookwriter.SaaS.request.PasswordResetRequest;
import com.ebookwriter.SaaS.request.RegisterRequest;
import com.ebookwriter.SaaS.response.AuthResponse;
import com.ebookwriter.SaaS.security.JwtProvider;
import com.ebookwriter.SaaS.service.RefreshTokenRotationResult;
import com.ebookwriter.SaaS.service.RefreshTokenService;
import com.ebookwriter.SaaS.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refreshToken";

    private final UserService userService;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return ResponseEntity.ok("Registration successful. Check your email to verify your account.");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {

        AuthResponse res = userService.login(request);

        ResponseCookie cookie = refreshCookie(res.getRefreshToken(),
                Duration.ofDays(request.isRememberMe() ? 30 : 1));

        // The refresh token lives only in the httpOnly cookie — never in the
        // JSON body — so JS on the page can't read it.
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(res.getToken(), null, res.getUser()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = REFRESH_COOKIE, required = false) String rawRefreshToken) {

        if (rawRefreshToken != null) {
            try {
                refreshTokenService.revokeToken(rawRefreshToken);
            } catch (Exception ignored) {
                // Already-invalid token: logout is a no-op, still clear cookie.
            }
        }

        ResponseCookie cookie = refreshCookie("", Duration.ZERO);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(value = REFRESH_COOKIE, required = false) String rawRefreshToken) {

        if (rawRefreshToken == null) {
            throw new RuntimeException("No refresh token");
        }

        RefreshToken token = refreshTokenService.validateRefreshToken(rawRefreshToken);

        String newAccessToken = jwtProvider.generateToken(token.getUser().getEmail(), token.isRememberMe());
        RefreshTokenRotationResult rotation = refreshTokenService.rotateToken(token);

        ResponseCookie cookie = refreshCookie(rotation.rawToken(),
                Duration.between(LocalDateTime.now(), rotation.expiresAt()));

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthResponse(newAccessToken, null, null));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDTO> me(Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("Unauthorized");
        }

        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        return ResponseEntity.ok(new UserDTO(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isEnabled()
        ));
    }

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAccount(@RequestParam UUID tokenId, @RequestParam String token) {
        userService.verifyAccount(tokenId, token);
        return ResponseEntity.ok("Your account has been activated.");
    }

    @PostMapping("/resend-verification-email")
    public ResponseEntity<String> resendVerificationEmail(@Valid @RequestBody EmailVerificationRequest request) {
        userService.resendVerificationEmail(request.getEmail());
        return ResponseEntity.ok("If the account exists and is unverified, a verification link has been sent.");
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@Valid @RequestBody EmailVerificationRequest request) {
        userService.forgotPassword(request.getEmail());
        return ResponseEntity.ok("If the account exists, a password reset link has been sent.");
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestParam UUID tokenId,
                                                @RequestParam String token,
                                                @Valid @RequestBody PasswordResetRequest request) {
        userService.resetPassword(tokenId, token, request);
        return ResponseEntity.ok("Password changed successfully.");
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
