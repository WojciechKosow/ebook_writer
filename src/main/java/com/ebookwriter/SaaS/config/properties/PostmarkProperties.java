package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "postmark")
public class PostmarkProperties {

    private Api api = new Api();
    private Timeout timeout = new Timeout();

    @Getter
    @Setter
    public static class Api {
        // Postmark's transactional email HTTP API. Set POSTMARK_SERVER_TOKEN in
        // the environment; left blank locally so the app still boots (sends
        // just fail auth).
        private String baseUrl = "https://api.postmarkapp.com";
        private String serverToken;
    }

    @Getter
    @Setter
    public static class Timeout {
        // Connect / read timeouts (ms) for the Reactor Netty HTTP client.
        private long connect = 5_000;
        private long read = 10_000;
    }
}
