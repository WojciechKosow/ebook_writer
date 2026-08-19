package com.ebookwriter.SaaS.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Dedup ledger for Stripe webhook events. Stripe may deliver the same event
 * more than once; the event id is the PK so a duplicate is rejected before any
 * fulfilment runs.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "processed_stripe_events")
public class ProcessedStripeEvent {

    @Id
    private String eventId;

    private String type;

    private LocalDateTime receivedAt;
}
