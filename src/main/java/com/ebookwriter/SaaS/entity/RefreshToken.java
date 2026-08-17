package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A rotating refresh token. The stored value is the bcrypt hash of the token
 * secret; the raw {@code <id>.<secret>} string is only ever held by the client
 * (in an httpOnly cookie). Rotation on every use plus reuse detection means a
 * stolen-then-replayed token revokes the whole session family.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    @Id
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(nullable = false)
    private boolean rememberMe;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private String createdByIp;
    private String userAgent;

    @Version
    private Long version;
}
