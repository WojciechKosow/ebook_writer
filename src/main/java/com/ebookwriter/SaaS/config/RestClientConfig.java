package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
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
@Configuration
public class RestClientConfig {

    @Bean(name = "postmarkRestClient")
    public RestClient postmarkRestClient(PostmarkProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getApi().getBaseUrl())
                .defaultHeader("X-Postmark-Server-Token", properties.getApi().getServerToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
