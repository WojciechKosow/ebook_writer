package com.ebookwriter.SaaS.repository;

import com.ebookwriter.SaaS.entity.StripeWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, String> {
}
