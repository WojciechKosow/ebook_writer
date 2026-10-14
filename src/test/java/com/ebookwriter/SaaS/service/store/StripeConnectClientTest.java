package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.config.properties.StripeConnectProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
}
