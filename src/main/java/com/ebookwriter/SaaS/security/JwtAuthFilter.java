package com.ebookwriter.SaaS.security;

import com.ebookwriter.SaaS.entity.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (!jwtProvider.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = jwtProvider.extractClaimsFromToken(token, Claims::getSubject);

        if (userId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        User user = customUserDetailsService.loadUserEntityById(userId);

        String tokenCredAtString = jwtProvider.extractClaimsFromToken(
                token,
                claims -> claims.get("credAt", String.class)
        );

        if (tokenCredAtString == null) {
            filterChain.doFilter(request, response);
            return;
        }

        LocalDateTime tokenCredAt = LocalDateTime.parse(tokenCredAtString);

        // Token minted before the last credential change (e.g. a password
        // reset) — reject it so a reset immediately kills old access tokens.
        if (user.getCredentialsUpdatedAt() != null &&
                tokenCredAt.isBefore(user.getCredentialsUpdatedAt())) {

            filterChain.doFilter(request, response);
            return;
        }

        UserDetails userDetails =
                customUserDetailsService.loadUserByUsername(user.getEmail());

        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

        authenticationToken.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
        );

        SecurityContextHolder.getContext().setAuthentication(authenticationToken);

        filterChain.doFilter(request, response);
    }
}
