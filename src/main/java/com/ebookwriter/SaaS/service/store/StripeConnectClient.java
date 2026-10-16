package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.model.v2.core.Account;
import com.stripe.model.v2.core.AccountLink;
import com.stripe.net.RequestOptions;
import com.stripe.param.v2.core.AccountCreateParams;
import com.stripe.param.v2.core.AccountLinkCreateParams;
import com.stripe.param.v2.core.AccountRetrieveParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

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

    /**
     * Accounts v2 (Stripe refuses v1 account creation for new Connect platforms).
     * Same model as the v1 controller properties: Stripe collects fees from the
     * account and covers losses, the author gets the full Stripe Dashboard (Stripe
     * collects requirements), and the account is a merchant accepting cards.
     */
    @Override
    public String createAccount(String email, String country, String storeId) throws StripeException {
        AccountCreateParams.Builder params = AccountCreateParams.builder()
                .setDashboard(AccountCreateParams.Dashboard.FULL)
                .setDefaults(AccountCreateParams.Defaults.builder()
                        .setResponsibilities(AccountCreateParams.Defaults.Responsibilities.builder()
                                .setFeesCollector(AccountCreateParams.Defaults.Responsibilities.FeesCollector.STRIPE)
                                .setLossesCollector(AccountCreateParams.Defaults.Responsibilities.LossesCollector.STRIPE)
                                .build())
                        .build())
                .setConfiguration(AccountCreateParams.Configuration.builder()
                        .setMerchant(AccountCreateParams.Configuration.Merchant.builder()
                                .setCapabilities(AccountCreateParams.Configuration.Merchant.Capabilities.builder()
                                        .setCardPayments(AccountCreateParams.Configuration.Merchant.Capabilities.CardPayments.builder()
                                                .setRequested(true)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .putMetadata("store_id", storeId);
        if (!isBlank(email)) {
            params.setContactEmail(email);
        }
        if (!isBlank(country)) {
            params.setIdentity(AccountCreateParams.Identity.builder().setCountry(country).build());
        }
        return client().v2().core().accounts().create(params.build()).getId();
    }

    @Override
    public String createAccountLink(String accountId, String refreshUrl, String returnUrl) throws StripeException {
        AccountLink link = client().v2().core().accountLinks().create(AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setUseCase(AccountLinkCreateParams.UseCase.builder()
                        .setType(AccountLinkCreateParams.UseCase.Type.ACCOUNT_ONBOARDING)
                        .setAccountOnboarding(AccountLinkCreateParams.UseCase.AccountOnboarding.builder()
                                .addConfiguration(AccountLinkCreateParams.UseCase.AccountOnboarding.Configuration.MERCHANT)
                                .setRefreshUrl(refreshUrl)
                                .setReturnUrl(returnUrl)
                                .build())
                        .build())
                .build());
        return link.getUrl();
    }

    /**
     * Ready to sell = the merchant {@code card_payments} capability is active (the
     * v2 equivalent of {@code charges_enabled}). "Currently due" = requirements
     * waiting on the author with a currently/past-due deadline.
     */
    @Override
    public AccountState retrieveAccount(String accountId) throws StripeException {
        Account account = client().v2().core().accounts().retrieve(accountId, AccountRetrieveParams.builder()
                .addInclude(AccountRetrieveParams.Include.CONFIGURATION__MERCHANT)
                .addInclude(AccountRetrieveParams.Include.REQUIREMENTS)
                .build());
        return toState(account);
    }

    static AccountState toState(Account account) {
        String cardStatus = Optional.ofNullable(account.getConfiguration())
                .map(Account.Configuration::getMerchant)
                .map(Account.Configuration.Merchant::getCapabilities)
                .map(Account.Configuration.Merchant.Capabilities::getCardPayments)
                .map(Account.Configuration.Merchant.Capabilities.CardPayments::getStatus)
                .orElse(null);
        List<Account.Requirements.Entry> entries = account.getRequirements() != null
                && account.getRequirements().getEntries() != null
                ? account.getRequirements().getEntries() : List.of();
        List<Account.Requirements.Entry> onUser = entries.stream()
                .filter(e -> "user".equals(e.getAwaitingActionFrom()))
                .toList();
        List<String> due = onUser.stream()
                .filter(e -> e.getMinimumDeadline() != null
                        && ("currently_due".equals(e.getMinimumDeadline().getStatus())
                        || "past_due".equals(e.getMinimumDeadline().getStatus())))
                .map(e -> e.getDescription() != null ? e.getDescription() : "requirement")
                .toList();
        if (!"active".equals(cardStatus)) {
            // Why it isn't ready yet: e.g. "pending" with entries awaiting "stripe" = Stripe still verifying.
            log.info("[StripeConnect] Account {} card_payments={} requirements awaiting user={} other={}",
                    account.getId(), cardStatus, onUser.size(), entries.size() - onUser.size());
        }
        return new AccountState(account.getId(), "active".equals(cardStatus), onUser.isEmpty(), due);
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
