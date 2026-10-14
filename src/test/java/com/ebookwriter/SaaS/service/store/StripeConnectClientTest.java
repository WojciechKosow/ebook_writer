package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import com.stripe.model.v2.core.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Live mode must be impossible without explicit production configuration. */
class StripeConnectClientTest {

    @Test
    void liveKeyIsRefusedUnlessLiveModeIsEnabled() {
        StripeConnectProperties props = new StripeConnectProperties();
        props.setSecretKey("sk_live_123");
        StripeConnectClient client = new StripeConnectClient(props);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> client.createAccount("a@example.com", "US", "store"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
        assertThrows(ResponseStatusException.class, () -> client.retrieveAccount("acct_1"));
    }

    @Test
    void restrictedLiveKeyIsAlsoRecognisedAsLive() {
        StripeConnectProperties props = new StripeConnectProperties();
        props.setSecretKey("rk_live_123");
        assertTrue(props.isLiveKey());
        props.setSecretKey("sk_test_123");
        assertFalse(props.isLiveKey());
    }

    @Test
    void missingKeyMeansNotConfigured() {
        StripeConnectClient client = new StripeConnectClient(new StripeConnectProperties());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> client.createAccountLink("acct_1", "r", "r"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }

    // ---- Accounts v2 → our three states -------------------------------------------

    @Test
    void activeCardPaymentsMeansReadyToSell() {
        StripeConnectGateway.AccountState state = StripeConnectClient.toState(account("active", List.of()));
        assertTrue(state.chargesEnabled());
        assertTrue(state.detailsSubmitted());
        assertTrue(state.currentlyDue().isEmpty());
    }

    @Test
    void pendingCapabilityWithUserRequirementsNeedsAction() {
        StripeConnectGateway.AccountState state = StripeConnectClient.toState(account("pending", List.of(
                entry("user", "currently_due", "Provide a bank account"),
                entry("user", "eventually_due", "Upload an ID later"),
                entry("stripe", "currently_due", "Stripe is reviewing"))));
        assertFalse(state.chargesEnabled());
        assertFalse(state.detailsSubmitted());
        assertEquals(List.of("Provide a bank account"), state.currentlyDue());
    }

    @Test
    void freshAccountWithoutConfigurationIsNotReady() {
        Account account = new Account();
        account.setId("acct_new");
        StripeConnectGateway.AccountState state = StripeConnectClient.toState(account);
        assertFalse(state.chargesEnabled());
        assertTrue(state.currentlyDue().isEmpty());
    }

    private static Account account(String cardStatus, List<Account.Requirements.Entry> entries) {
        Account.Configuration.Merchant.Capabilities.CardPayments card =
                new Account.Configuration.Merchant.Capabilities.CardPayments();
        card.setStatus(cardStatus);
        Account.Configuration.Merchant.Capabilities caps = new Account.Configuration.Merchant.Capabilities();
        caps.setCardPayments(card);
        Account.Configuration.Merchant merchant = new Account.Configuration.Merchant();
        merchant.setCapabilities(caps);
        Account.Configuration configuration = new Account.Configuration();
        configuration.setMerchant(merchant);
        Account.Requirements requirements = new Account.Requirements();
        requirements.setEntries(entries);
        Account account = new Account();
        account.setId("acct_1");
        account.setConfiguration(configuration);
        account.setRequirements(requirements);
        return account;
    }

    private static Account.Requirements.Entry entry(String awaiting, String deadline, String description) {
        Account.Requirements.Entry.MinimumDeadline d = new Account.Requirements.Entry.MinimumDeadline();
        d.setStatus(deadline);
        Account.Requirements.Entry e = new Account.Requirements.Entry();
        e.setAwaitingActionFrom(awaiting);
        e.setMinimumDeadline(d);
        e.setDescription(description);
        return e;
    }
}
