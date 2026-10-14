package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Real Stripe implementation of {@link StripeConnectGateway}. Uses its own
 * {@link StripeClient} with {@code stripe.connect.secret-key} — never the global
 * {@code Stripe.apiKey} of the subscription flow — and refuses a live key unless
 * {@code stripe.connect.live-enabled=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StripeConnectClient implements StripeConnectGateway {

    private final StripeConnectProperties properties;

    private volatile StripeClient client;

    @PostConstruct
    void logMode() {
        if (isBlank(properties.getSecretKey())) {
            log.warn("[StripeConnect] stripe.connect.secret-key is not set — creator store payments are "
                    + "disabled until STRIPE_CONNECT_SECRET_KEY is set");
        } else if (properties.isLiveKey() && !properties.isLiveEnabled()) {
            log.error("[StripeConnect] A LIVE key is configured but stripe.connect.live-enabled is false — "
                    + "every Connect call will be refused. Use an sk_test_ key, or set "
                    + "STRIPE_CONNECT_LIVE_ENABLED=true deliberately in production.");
        } else {
            log.info("[StripeConnect] Creator store payments in {} mode",
                    properties.isLiveKey() ? "LIVE" : "TEST");
        }
    }

    @Override
    public String createAccount(String email, String country, String storeId) throws StripeException {
        AccountCreateParams.Builder params = AccountCreateParams.builder()
                .setController(AccountCreateParams.Controller.builder()
                        .setLosses(AccountCreateParams.Controller.Losses.builder()
                                .setPayments(AccountCreateParams.Controller.Losses.Payments.STRIPE)
                                .build())
                        .setFees(AccountCreateParams.Controller.Fees.builder()
                                .setPayer(AccountCreateParams.Controller.Fees.Payer.ACCOUNT)
                                .build())
                        .setRequirementCollection(AccountCreateParams.Controller.RequirementCollection.STRIPE)
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                                .setType(AccountCreateParams.Controller.StripeDashboard.Type.FULL)
                                .build())
                        .build())
                .putMetadata("store_id", storeId);
        if (!isBlank(email)) {
            params.setEmail(email);
        }
        if (!isBlank(country)) {
            params.setCountry(country);
        }
        return client().v1().accounts().create(params.build()).getId();
    }

    @Override
    public String createAccountLink(String accountId, String refreshUrl, String returnUrl) throws StripeException {
        AccountLink link = client().v1().accountLinks().create(AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setRefreshUrl(refreshUrl)
                .setReturnUrl(returnUrl)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .build());
        return link.getUrl();
    }

    @Override
    public AccountState retrieveAccount(String accountId) throws StripeException {
        Account account = client().v1().accounts().retrieve(accountId);
        List<String> due = account.getRequirements() != null && account.getRequirements().getCurrentlyDue() != null
                ? account.getRequirements().getCurrentlyDue()
                : List.of();
        return new AccountState(account.getId(),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getDetailsSubmitted()),
                due);
    }

    @Override
    public CreatedSession createCheckoutSession(String accountId, SessionCreateParams params) throws StripeException {
        Session session = client().v1().checkout().sessions().create(params,
                RequestOptions.builder().setStripeAccount(accountId).build());
        return new CreatedSession(session.getId(), session.getUrl());
    }

    private StripeClient client() {
        String key = properties.getSecretKey();
        if (isBlank(key)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Store payments are not configured");
        }
        if (properties.isLiveKey() && !properties.isLiveEnabled()) {
            log.error("[StripeConnect] Refusing a live-mode call: stripe.connect.live-enabled is false");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Store payments are not available");
        }
        StripeClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = new StripeClient(key.trim());
                }
                c = client;
            }
        }
        return c;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
