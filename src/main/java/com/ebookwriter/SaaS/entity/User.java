package com.ebookwriter.SaaS.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Builder
@Getter
@Setter
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    @NotBlank
    private String displayName;

    @Email
    @NotBlank(message = "Email mustn't be null")
    @Column(unique = true, nullable = false)
    private String email;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Builder.Default
    private boolean enabled = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Brute-force protection: incremented on every failed login and reset on
    // success. Once it crosses the threshold the account is locked until
    // lockUntil (see UserServiceImpl.login).
    private int failedLoginAttempts;
    private LocalDateTime lockUntil;

    // Bumped whenever the credentials change (password reset). Access tokens
    // issued before this instant are rejected by JwtAuthFilter, so a password
    // reset invalidates every outstanding access token.
    private LocalDateTime credentialsUpdatedAt;

    // Stripe customer id — created lazily on first checkout and reused for all
    // future purchases, subscriptions and the billing portal.
    private String stripeCustomerId;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.credentialsUpdatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
