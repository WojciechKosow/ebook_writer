package com.ebookwriter.SaaS.service.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.InvoicePayment;
import com.stripe.model.InvoicePaymentCollection;
import com.stripe.param.InvoicePaymentListParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Finds the subscription invoice a Stripe payment paid — needed to tell which
 * billing period's credits a refunded or disputed subscription charge granted.
 * Kept behind its own bean so the webhook logic can be tested without Stripe.
 */
@Slf4j
@Component
public class StripeInvoiceLookup {

    /** The invoice paid by {@code paymentIntentId}, if Stripe knows one. */
    public Optional<String> invoiceForPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return Optional.empty();
        }
        try {
            InvoicePaymentCollection payments = InvoicePayment.list(InvoicePaymentListParams.builder()
                    .setPayment(InvoicePaymentListParams.Payment.builder()
                            .setType(InvoicePaymentListParams.Payment.Type.PAYMENT_INTENT)
                            .setPaymentIntent(paymentIntentId)
                            .build())
                    .setLimit(1L)
                    .build());
            return payments.getData().stream().findFirst().map(InvoicePayment::getInvoice);
        } catch (StripeException e) {
            log.warn("[Stripe] Could not look up the invoice for payment_intent {}: {}", paymentIntentId, e.getMessage());
            return Optional.empty();
        }
    }
}
