package com.ebookwriter.SaaS.service;

import java.time.LocalDateTime;

public record RefreshTokenRotationResult(
        String rawToken,
        LocalDateTime expiresAt
) {
}
