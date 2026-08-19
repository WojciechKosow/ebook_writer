package com.ebookwriter.SaaS.security;

import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class JwtProvider {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration}")
    private long expiration;

    @Value("${jwt.remember-me-expiration:2592000000}")
    private long rememberMeExpiration;

    private final UserRepository userRepository;

    /**
     * Access token. The subject is the user id (not the email, so an email
     * change never invalidates in-flight tokens by identity), and the
     * {@code credAt} claim carries the credentials-updated timestamp so the
     * auth filter can reject tokens minted before the last password change.
     */
    public String generateToken(String email, boolean rememberMe) {

        User user = userRepository.findByEmail(email).orElseThrow();

        Date now = new Date();
        long validity = rememberMe ? rememberMeExpiration : expiration;
        Date expiry = new Date(now.getTime() + validity);

        return Jwts.builder()
                .setSubject(user.getId().toString())
                .claim("credAt", user.getCredentialsUpdatedAt().toString())
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public <T> T extractClaimsFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
        return claimsResolver.apply(claims);
    }
}
