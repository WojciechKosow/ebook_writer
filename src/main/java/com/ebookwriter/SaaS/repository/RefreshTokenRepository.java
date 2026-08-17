package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.RefreshToken;
import com.ebookwriter.SaaS.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    List<RefreshToken> findByUserAndRevokedFalse(User user);

    /**
     * Atomically revoke a token only if it is currently active. Returns the
     * number of rows updated: 0 means the token was already revoked, which is
     * how {@link com.ebookwriter.SaaS.service.RefreshTokenService} detects a
     * replay of an already-rotated token.
     */
    @Modifying
    @Query("""
       update RefreshToken t
       set t.revoked = true
       where t.id = :id
       and t.revoked = false
       """)
    int revokeIfNotRevoked(UUID id);
}
