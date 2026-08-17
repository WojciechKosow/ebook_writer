package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "postmark")
public class PostmarkProperties {

    private Api api = new Api();

    @Getter
    @Setter
    public static class Api {
        // Postmark's transactional email HTTP API. Set POSTMARK_SERVER_TOKEN in
        // the environment; left blank locally so the app still boots (sends
        // just fail auth).
        private String baseUrl = "https://api.postmarkapp.com";
        private String serverToken;
    }
}
