package com.ebookwriter.SaaS.service.payment;

import com.ebookwriter.SaaS.config.properties.StripeProperties;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.UserRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.billingportal.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.billingportal.SessionCreateParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps a user to a stable Stripe Customer (needed for subscriptions so invoices
 * and renewals attach to one entity). Created lazily and reused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeCustomerService {

    private final UserRepository userRepository;
    private final StripeProperties stripeProperties;

    @Transactional
    public String getOrCreateCustomerId(User user) throws StripeException {
        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            return user.getStripeCustomerId();
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getDisplayName())
                .putMetadata("userId", user.getId().toString())
                .build();

        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        log.info("[Stripe] Created customer {} for user {}", customer.getId(), user.getId());
        return customer.getId();
    }

    /**
     * Create a Stripe billing-portal session so the user can manage their
     * subscription, payment method, and invoices on Stripe-hosted pages.
     * Returns the URL to redirect the browser to. Requires the Customer Portal
     * to be configured once in the Stripe Dashboard.
     */
    @Transactional
    public String createBillingPortalUrl(User user) throws StripeException {
        String customerId = getOrCreateCustomerId(user);
        SessionCreateParams params = SessionCreateParams.builder()
                .setCustomer(customerId)
                .setReturnUrl(stripeProperties.getPortalReturnUrl())
                .build();
        return Session.create(params).getUrl();
    }
}
