package com.ebookwriter.SaaS.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Dedup ledger for the Stripe <b>Connect</b> webhook (creator store payments).
 * Kept separate from {@link ProcessedStripeEvent}, which belongs to the
 * platform's own subscription webhook. The event id is the PK, so a duplicate
 * delivery can never be processed twice.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "stripe_webhook_event")
public class StripeWebhookEvent {

    @Id
    @Column(name = "event_id", length = 255)
    private String eventId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private LocalDateTime processedAt;
}
