package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.TokenType;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, UUID> {
    Optional<UserToken> findByUserAndTypeAndUsedFalse(User user, TokenType type);
}
