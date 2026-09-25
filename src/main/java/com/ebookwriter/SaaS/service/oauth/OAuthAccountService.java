package com.ebookwriter.SaaS.service.oauth;

import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserIdentity;
import com.ebookwriter.SaaS.repository.UserIdentityRepository;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.ebookwriter.SaaS.security.RequestContextUtil;
import com.ebookwriter.SaaS.security.SecurityEventService;
import com.ebookwriter.SaaS.security.SecurityEventType;
import com.ebookwriter.SaaS.security.oauth.ExternalIdentity;
import com.ebookwriter.SaaS.security.oauth.OAuthErrorCode;
import com.ebookwriter.SaaS.security.oauth.OAuthException;
import com.ebookwriter.SaaS.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Maps a verified external identity (e.g. Google) onto the ONE canonical
 * Scrivetta {@link User}. Provider-neutral: works on {@link ExternalIdentity}.
 *
 * <p>Resolution order — idempotent, never creates a duplicate account:
 * <ol>
 *   <li><b>Already linked</b> — (provider, subject) exists: sign in that user.
 *       Always wins, even if the email at the provider has since changed.</li>
 *   <li>Otherwise the provider MUST report {@code email_verified=true}; an
 *       unverified email is never used to create or link an account.</li>
 *   <li><b>Existing account with that email</b> — link the identity to it, so
 *       password and Google both sign in to the same user (same ebooks, credits,
 *       subscription). If that account had never verified its email, whoever
 *       chose its password never proved they own the address; the verified
 *       provider login does, so the unproven password is replaced with a random
 *       one (the owner can set their own via "Forgot password"), existing
 *       sessions are revoked, and the account is activated. This closes the
 *       "pre-registered account" takeover.</li>
 *   <li>If that account is already linked to a <em>different</em> identity at
 *       the same provider, refuse ({@code account_conflict}) — never silently
 *       swap which external account controls it.</li>
 *   <li><b>No account</b> — create one: activated (the provider verified the
 *       email), with a random unusable password. The user can add a real
 *       password any time via "Forgot password".</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class OAuthAccountService {

    private static final int MAX_DISPLAY_NAME = 100;

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final SecurityEventService securityEventService;
    private final RequestContextUtil requestContextUtil;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public User resolveUser(ExternalIdentity external) {

        Optional<UserIdentity> linked = identityRepository
                .findByProviderAndProviderUserId(external.provider(), external.subject());
        if (linked.isPresent()) {
            UserIdentity identity = linked.get();
            identity.setLastLoginAt(LocalDateTime.now());
            if (external.email() != null) identity.setEmail(normalizeEmail(external.email()));
            if (external.pictureUrl() != null) identity.setPictureUrl(external.pictureUrl());
            return identity.getUser();
        }

        if (!external.emailVerified() || external.email() == null || external.email().isBlank()) {
            throw new OAuthException(OAuthErrorCode.EMAIL_NOT_VERIFIED,
                    external.provider() + " did not report a verified email");
        }
        String email = normalizeEmail(external.email());

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (identityRepository.findByUserAndProvider(user, external.provider()).isPresent()) {
                throw new OAuthException(OAuthErrorCode.ACCOUNT_CONFLICT,
                        "User " + user.getId() + " is already linked to a different "
                                + external.provider() + " account");
            }
            if (!user.isEnabled()) {
                secureUnverifiedAccount(user);
            }
            link(user, external, email);
            logEvent(SecurityEventType.OAUTH_ACCOUNT_LINKED, email);
            return user;
        }

        User user = new User();
        user.setEmail(email);
        user.setDisplayName(displayName(external.name(), email));
        user.setPassword(passwordEncoder.encode(randomSecret()));
        user.setEnabled(true);
        userRepository.save(user);

        link(user, external, email);
        logEvent(SecurityEventType.OAUTH_ACCOUNT_CREATED, email);
        return user;
    }

    // ------------------------------------------------------------------------

    private void secureUnverifiedAccount(User user) {
        user.setPassword(passwordEncoder.encode(randomSecret()));
        user.setCredentialsUpdatedAt(LocalDateTime.now());
        user.setFailedLoginAttempts(0);
        user.setLockUntil(null);
        user.setEnabled(true);
        userRepository.save(user);
        refreshTokenService.revokeAllUserTokens(user);
    }

    private void link(User user, ExternalIdentity external, String email) {
        identityRepository.saveAndFlush(UserIdentity.builder()
                .user(user)
                .provider(external.provider())
                .providerUserId(external.subject())
                .email(email)
                .pictureUrl(external.pictureUrl())
                .createdAt(LocalDateTime.now())
                .lastLoginAt(LocalDateTime.now())
                .build());
    }

    private void logEvent(SecurityEventType type, String email) {
        securityEventService.log(type, email,
                requestContextUtil.getClientIp(), requestContextUtil.getUserAgent());
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String displayName(String name, String email) {
        String candidate = name == null ? "" : name.strip();
        if (candidate.isEmpty()) {
            candidate = email.substring(0, Math.max(1, email.indexOf('@')));
        }
        return candidate.length() > MAX_DISPLAY_NAME ? candidate.substring(0, MAX_DISPLAY_NAME) : candidate;
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
