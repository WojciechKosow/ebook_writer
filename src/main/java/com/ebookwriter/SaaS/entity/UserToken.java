package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One-time, single-use token backing the email-based flows (account
 * verification and password reset). Only the bcrypt hash of the raw token is
 * stored — the raw value lives only in the emailed link, so a database leak
 * can't be replayed against these endpoints.
 */
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_tokens", indexes = {
        @Index(columnList = "user_id"),
        @Index(columnList = "expiresAt"),
        @Index(columnList = "user_id, type, used")
})
public class UserToken {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    private TokenType type;

    @Column(nullable = false)
    private String tokenHash;

    private LocalDateTime expiresAt;

    private boolean used;

    private LocalDateTime createdAt;
}
