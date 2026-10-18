package com.ebookwriter.SaaS.service;

import java.util.UUID;

public interface MailService {
    void sendVerificationEmail(String to, UUID tokenId, String token);
    void sendPasswordResetEmail(String to, UUID tokenId, String token);

    /**
     * A creator-store buyer's download link, sent once the payment is confirmed.
     * {@code linkedToAccount}: the purchase is already in the buyer's My Purchases.
     */
    void sendStorePurchaseEmail(String to, String productTitle, String storeName, String downloadUrl,
                                boolean linkedToAccount);
}
