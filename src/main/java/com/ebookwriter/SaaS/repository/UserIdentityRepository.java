package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    @Query("select i from UserIdentity i join fetch i.user where i.provider = :provider and i.providerUserId = :providerUserId")
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<UserIdentity> findByUserAndProvider(User user, String provider);
}
