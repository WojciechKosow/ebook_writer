package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Sends transactional email through Postmark's HTTP API rather than SMTP —
 * most PaaS containers block outbound SMTP ports outright, so port 587 times
 * out regardless of credentials. The API rides over plain HTTPS, which is
 * never blocked.
 */
@Slf4j
@Configuration
public class RestClientConfig {

    @Bean(name = "postmarkRestClient")
    public RestClient postmarkRestClient(PostmarkProperties properties) {
        String token = properties.getApi().getServerToken();
        // Log presence + length only (never the secret) so a missing/blank token
        // — the usual cause of "no emails, nothing in Postmark Activity" — is
        // obvious at startup.
        log.info("[Postmark] Server token loaded: {} (length {})",
                token != null && !token.isBlank(), token == null ? 0 : token.length());
        return RestClient.builder()
                .baseUrl(properties.getApi().getBaseUrl())
                .defaultHeader("X-Postmark-Server-Token", properties.getApi().getServerToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
