package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.security.JwtProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived, signed download links for finished PDFs.
 *
 * <p>Why: downloading a multi-megabyte PDF through JavaScript ({@code fetch} →
 * blob) is fragile — download-manager extensions and antivirus web shields
 * intercept {@code application/pdf} attachments mid-request, which makes the
 * {@code fetch} fail even though the server answered 200. A plain link lets the
 * browser download the file natively (streamed, no CORS, compatible with those
 * tools). The link carries a signed token instead of the Authorization header
 * (a plain navigation can't send one).
 *
 * <p>The token is scoped to one ebook and one user, expires quickly, and has its
 * own type claim. It can never act as an access token: the auth filter only reads
 * the Authorization header and requires a {@code credAt} claim this token lacks.
 */
@Service
@RequiredArgsConstructor
public class PdfDownloadLinkService {

    /** How long a link stays valid — long enough to click, short enough not to leak. */
    static final long TTL_MILLIS = 5 * 60 * 1000L;

    private static final String TYPE_CLAIM = "typ";
    private static final String TYPE = "pdf-download";
    private static final String EBOOK_CLAIM = "ebook";

    private final JwtProvider jwtProvider;

    /** Issue a token allowing {@code userId} to download {@code ebookId}'s PDF. */
    public String issue(UUID ebookId, UUID userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(userId.toString())
                .claim(TYPE_CLAIM, TYPE)
                .claim(EBOOK_CLAIM, ebookId.toString())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + TTL_MILLIS))
                .signWith(jwtProvider.getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * The user a token authorises for this ebook, or empty when the token is
     * invalid, expired, of another type, or issued for a different ebook.
     */
    public Optional<UUID> verify(String token, UUID ebookId) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtProvider.getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            if (!TYPE.equals(claims.get(TYPE_CLAIM, String.class))
                    || !ebookId.toString().equals(claims.get(EBOOK_CLAIM, String.class))) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
