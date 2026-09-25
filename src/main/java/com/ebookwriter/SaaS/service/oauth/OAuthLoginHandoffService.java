package com.ebookwriter.SaaS.service.oauth;

import com.ebookwriter.SaaS.entity.TokenType;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserToken;
import com.ebookwriter.SaaS.repository.UserTokenRepository;
import com.ebookwriter.SaaS.security.oauth.OAuthErrorCode;
import com.ebookwriter.SaaS.security.oauth.OAuthException;
import com.ebookwriter.SaaS.security.oauth.OAuthStateService;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Hands a completed OAuth login from the backend callback to the frontend
 * without putting any session credential in a URL.
 *
 * <p>The callback redirects to the frontend with a one-time code (in the URL
 * fragment, so it never reaches a server log or Referer). The frontend POSTs it
 * to {@code /api/auth/oauth2/exchange} and receives exactly what a password
 * login returns — access token in the body, refresh token in the httpOnly
 * cookie. The code is single-use, expires after {@link #TTL}, is stored only as
 * a bcrypt hash (reusing {@link UserToken}), and is bound to the {@code clientState}
 * the frontend generated before leaving for Google — a code injected into
 * another browser (login CSRF) is useless there.
 */
@Service
@RequiredArgsConstructor
public class OAuthLoginHandoffService {

    static final Duration TTL = Duration.ofMinutes(2);

    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    /** @param clientStateHash {@link OAuthStateService#sha256} of the frontend's clientState */
    @Transactional
    public String issue(User user, String clientStateHash) {
        UUID tokenId = UUID.randomUUID();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        userTokenRepository.save(UserToken.builder()
                .id(tokenId)
                .user(user)
                .type(TokenType.OAUTH_LOGIN)
                .tokenHash(passwordEncoder.encode(binding(secret, clientStateHash)))
                .expiresAt(LocalDateTime.now().plus(TTL))
                .used(false)
                .createdAt(LocalDateTime.now())
                .build());

        return tokenId + "." + secret;
    }

    /** Consume a code and return its user. @throws OAuthException INVALID_LOGIN_CODE */
    @Transactional
    public User redeem(String rawCode, String clientState) {
        if (rawCode == null || clientState == null) throw invalid("missing code or client state");

        int dot = rawCode.indexOf('.');
        if (dot <= 0 || dot == rawCode.length() - 1) throw invalid("malformed code");

        UUID tokenId;
        try {
            tokenId = UUID.fromString(rawCode.substring(0, dot));
        } catch (IllegalArgumentException e) {
            throw invalid("malformed code");
        }
        String secret = rawCode.substring(dot + 1);

        UserToken token = userTokenRepository.findById(tokenId)
                .orElseThrow(() -> invalid("unknown code"));

        if (token.getType() != TokenType.OAUTH_LOGIN
                || token.isUsed()
                || token.getExpiresAt().isBefore(LocalDateTime.now())
                || !passwordEncoder.matches(binding(secret, OAuthStateService.sha256(clientState)),
                token.getTokenHash())) {
            throw invalid("code used, expired or not bound to this client");
        }

        // Load the user before markUsed clears the persistence context.
        User user = (User) Hibernate.unproxy(token.getUser());

        // Atomic single use: a concurrent replay loses this race.
        if (userTokenRepository.markUsed(tokenId) != 1) {
            throw invalid("code already used");
        }
        return user;
    }

    /** SHA-256 keeps the bcrypt input under its 72-byte limit while covering both values. */
    private static String binding(String secret, String clientStateHash) {
        return OAuthStateService.sha256(secret + ":" + clientStateHash);
    }

    private static OAuthException invalid(String why) {
        return new OAuthException(OAuthErrorCode.INVALID_LOGIN_CODE, "OAuth login code rejected: " + why);
    }
}
