package com.ebookwriter.SaaS.entity;

/** Lifecycle of a buyer's store order. Only the Connect webhook moves it past PENDING. */
public enum StoreOrderStatus {
    PENDING,
    PAID,
    EXPIRED,
    REFUNDED
}
