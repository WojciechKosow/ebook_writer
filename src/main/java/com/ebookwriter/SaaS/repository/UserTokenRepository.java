package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.TokenType;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {
    Optional<UserToken> findByUserAndTypeAndUsedFalse(User user, TokenType type);

    /** Atomically consume a token: 1 if this call flipped it to used, 0 if it was already used. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserToken t set t.used = true where t.id = :id and t.used = false")
    int markUsed(UUID id);
}
