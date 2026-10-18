package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Emails the buyer their download link once the PAID order has committed, so a
 * rolled-back webhook can never send a link to a grant that doesn't exist.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorePurchaseMailer {

    private final MailService mailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPurchase(StorePurchaseCompletedEvent event) {
        if (event.buyerEmail() == null || event.buyerEmail().isBlank()) {
            log.error("[StoreDownload] Order {} is PAID but has no buyer email — download link NOT sent",
                    event.orderId());
            return;
        }
        // Fragment, not path: never sent to servers or recorded by analytics.
        String url = frontendUrl.replaceAll("/+$", "") + "/download#" + event.downloadToken();
        try {
            mailService.sendStorePurchaseEmail(event.buyerEmail(), event.productTitle(), event.storeName(), url,
                    event.linkedToAccount());
            log.info("[StoreDownload] Download link sent for order {}", event.orderId());
        } catch (Exception e) {
            // TODO(store): delivery failure handling (resend) is a follow-up — the
            // order stays PAID; log loudly so it can be handled.
            log.error("[StoreDownload] Could not email the download link for PAID order {}: {}",
                    event.orderId(), e.getMessage());
        }
    }
}
