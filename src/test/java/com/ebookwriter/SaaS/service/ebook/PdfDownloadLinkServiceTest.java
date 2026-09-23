package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.security.JwtProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Key;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Signed PDF links authorise exactly one user for exactly one ebook, briefly. */
class PdfDownloadLinkServiceTest {

    private final Key key = Keys.hmacShaKeyFor("0123456789abcdef0123456789abcdef0123456789abcdef".getBytes());
    private PdfDownloadLinkService links;
    private final UUID ebook = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        JwtProvider jwt = mock(JwtProvider.class);
        when(jwt.getSigningKey()).thenReturn(key);
        links = new PdfDownloadLinkService(jwt);
    }

    @Test
    void aFreshLinkAuthorisesItsUserForItsEbook() {
        assertEquals(Optional.of(user), links.verify(links.issue(ebook, user), ebook));
    }

    @Test
    void aLinkForOneEbookCannotDownloadAnother() {
        assertTrue(links.verify(links.issue(ebook, user), UUID.randomUUID()).isEmpty());
    }

    @Test
    void aTamperedOrMissingTokenIsRejected() {
        String token = links.issue(ebook, user);
        assertTrue(links.verify(token.substring(0, token.length() - 2) + "xx", ebook).isEmpty());
        assertTrue(links.verify(null, ebook).isEmpty());
        assertTrue(links.verify("not-a-jwt", ebook).isEmpty());
    }

    @Test
    void anOrdinaryAccessTokenIsNotADownloadLink() {
        String access = Jwts.builder().setSubject(user.toString()).claim("credAt", "2026-01-01T00:00")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, SignatureAlgorithm.HS256).compact();
        assertTrue(links.verify(access, ebook).isEmpty());
    }

    @Test
    void anExpiredLinkIsRejected() {
        String expired = Jwts.builder().setSubject(user.toString()).claim("typ", "pdf-download")
                .claim("ebook", ebook.toString())
                .setExpiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key, SignatureAlgorithm.HS256).compact();
        assertTrue(links.verify(expired, ebook).isEmpty());
    }
}
