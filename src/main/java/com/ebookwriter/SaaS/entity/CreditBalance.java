package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A user's current credit balance, keyed by user id. The balance is a
 * materialised running total; every change is also recorded as a
 * {@link CreditTransaction}. Spends use a conditional atomic UPDATE
 * (balance >= amount) so concurrent generations can never overspend or drive
 * the balance negative.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "credit_balances")
public class CreditBalance {

    @Id
    private UUID userId;

    @Column(nullable = false)
    private int balance;
}
