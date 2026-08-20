package com.ebookwriter.SaaS.service;

import com.ebookwriter.SaaS.dto.UserDTO;
import com.ebookwriter.SaaS.entity.TokenType;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserToken;
import com.ebookwriter.SaaS.exceptions.EmailAlreadyExistsException;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.repository.UserTokenRepository;
import com.ebookwriter.SaaS.request.LoginRequest;
import com.ebookwriter.SaaS.request.PasswordResetRequest;
import com.ebookwriter.SaaS.request.RegisterRequest;
import com.ebookwriter.SaaS.response.AuthResponse;
import com.ebookwriter.SaaS.security.JwtProvider;
import com.ebookwriter.SaaS.security.RequestContextUtil;
import com.ebookwriter.SaaS.security.SecurityEventService;
import com.ebookwriter.SaaS.security.SecurityEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 5;
    private static final int TOKEN_TTL_MINUTES = 30;

    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final MailService mailService;
    private final SecurityEventService securityEventService;
    private final RequestContextUtil requestContextUtil;
    private final RefreshTokenService refreshTokenService;

    @Override
    public AuthResponse register(RegisterRequest request) {

        if (userRepository.existsByEmail(request.getEmail().toLowerCase())) {
            throw new EmailAlreadyExistsException("Email is already in use");
        }

        User user = new User();
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail().toLowerCase());
        user.setPassword(passwordEncoder.encode(request.getPassword()));

        // New accounts start disabled and are activated by clicking the link in
        // the verification email (see verifyAccount).
        user.setEnabled(false);

        userRepository.save(user);

        issueTokenAndSendVerification(user);

        return new AuthResponse(null, null, mapToDTO(user));
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {

        User user = userRepository.findByEmail(request.getEmail().toLowerCase())
                .orElseThrow(() -> new RuntimeException("Invalid password or email"));

        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {

            securityEventService.log(
                    SecurityEventType.ACCOUNT_LOCKED,
                    user.getEmail(),
                    requestContextUtil.getClientIp(),
                    requestContextUtil.getUserAgent()
            );

            throw new RuntimeException("Account is temporarily locked. Try again later.");
        }

        if (user.getCredentialsUpdatedAt() == null) {
            user.setCredentialsUpdatedAt(LocalDateTime.now());
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {

            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);

            if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
                user.setLockUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                user.setFailedLoginAttempts(0);

                securityEventService.log(
                        SecurityEventType.ACCOUNT_LOCKED,
                        user.getEmail(),
                        requestContextUtil.getClientIp(),
                        requestContextUtil.getUserAgent()
                );
            }

            securityEventService.log(
                    SecurityEventType.FAILED_LOGIN,
                    user.getEmail(),
                    requestContextUtil.getClientIp(),
                    requestContextUtil.getUserAgent()
            );

            userRepository.save(user);
            throw new RuntimeException("Invalid password or email");
        }

        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        userRepository.save(user);

        if (!user.isEnabled()) {
            throw new RuntimeException("Please verify your email before logging in.");
        }

        String accessToken = jwtProvider.generateToken(user.getEmail(), request.isRememberMe());
        String refreshToken = refreshTokenService.createRefreshToken(user, request.isRememberMe());

        return new AuthResponse(accessToken, refreshToken, mapToDTO(user));
    }

    @Override
    @Transactional
    public void verifyAccount(UUID tokenId, String rawToken) {

        UserToken token = validateOneTimeToken(tokenId, rawToken, TokenType.EMAIL_VERIFICATION);

        User user = token.getUser();
        user.setEnabled(true);
        token.setUsed(true);

        userRepository.save(user);
        userTokenRepository.save(token);
    }

    @Override
    public void resendVerificationEmail(String email) {

        Optional<User> userOptional = userRepository.findByEmail(email.toLowerCase());
        if (userOptional.isEmpty()) {
            return;
        }

        User user = userOptional.get();
        if (user.isEnabled()) {
            return;
        }

        userTokenRepository.findByUserAndTypeAndUsedFalse(user, TokenType.EMAIL_VERIFICATION)
                .ifPresent(userTokenRepository::delete);

        issueTokenAndSendVerification(user);
    }

    @Override
    public void forgotPassword(String email) {

        Optional<User> userOptional = userRepository.findByEmail(email.toLowerCase());
        if (userOptional.isEmpty()) {
            // Don't reveal whether the address is registered.
            return;
        }

        User user = userOptional.get();

        userTokenRepository.findByUserAndTypeAndUsedFalse(user, TokenType.PASSWORD_RESET)
                .ifPresent(userTokenRepository::delete);

        UUID tokenId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();

        userTokenRepository.save(buildToken(user, tokenId, rawToken, TokenType.PASSWORD_RESET));
        mailService.sendPasswordResetEmail(user.getEmail(), tokenId, rawToken);

        securityEventService.log(
                SecurityEventType.PASSWORD_RESET_REQUEST,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    @Override
    @Transactional
    public void resetPassword(UUID tokenId, String rawToken, PasswordResetRequest request) {

        UserToken token = validateOneTimeToken(tokenId, rawToken, TokenType.PASSWORD_RESET);

        User user = token.getUser();
        user.setCredentialsUpdatedAt(LocalDateTime.now());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        // A password change kills every active session.
        refreshTokenService.revokeAllUserTokens(user);

        token.setUsed(true);
        userTokenRepository.save(token);

        securityEventService.log(
                SecurityEventType.PASSWORD_CHANGED,
                user.getEmail(),
                requestContextUtil.getClientIp(),
                requestContextUtil.getUserAgent()
        );
    }

    // ------------------------------------------------------------------------

    private void issueTokenAndSendVerification(User user) {

        UUID tokenId = UUID.randomUUID();
        String rawToken = UUID.randomUUID().toString();

        userTokenRepository.save(buildToken(user, tokenId, rawToken, TokenType.EMAIL_VERIFICATION));

        try {
            mailService.sendVerificationEmail(user.getEmail(), tokenId, rawToken);
        } catch (Exception e) {
            // The account and token are already persisted — a mail-provider
            // hiccup shouldn't fail the whole registration. The user can hit
            // resend-verification-email once delivery is restored.
            log.error("Verification email failed to send for {}", user.getEmail(), e);
        }
    }

    private UserToken buildToken(User user, UUID tokenId, String rawToken, TokenType type) {
        return UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(type)
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private UserToken validateOneTimeToken(UUID tokenId, String rawToken, TokenType expectedType) {

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (token.isUsed()) {
            throw new RuntimeException("Token already used");
        }

        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token expired");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new RuntimeException("Invalid token");
        }

        if (token.getType() != expectedType) {
            throw new RuntimeException("Invalid token");
        }

        return token;
    }

    private UserDTO mapToDTO(User user) {
        return new UserDTO(
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                user.isEnabled()
        );
    }
}
