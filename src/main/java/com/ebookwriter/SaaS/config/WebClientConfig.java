package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.PostmarkProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Sends transactional email through Postmark's HTTP API rather than SMTP —
 * most PaaS containers (Railway, etc.) block outbound SMTP ports outright, so
 * port 587 to smtp.postmarkapp.com times out regardless of credentials. The
 * API rides over plain HTTPS, which is never blocked.
 *
 * <p>Uses a reactive {@link WebClient} backed by an explicit Reactor Netty
 * connector with connect/read timeouts — the same setup as the bossAi service,
 * where the plain RestClient stack was not reliably delivering sends. A response
 * filter logs Postmark's error body verbatim (bad token, unverified sender,
 * pending-approval account, …) without swallowing the exception.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    private final PostmarkProperties postmarkProperties;

    @Bean(name = "postmarkWebClient")
    public WebClient postmarkWebClient() {
        String token = postmarkProperties.getApi().getServerToken();
        // Log presence + length only (never the secret) so a missing/blank token
        // — the usual cause of "no emails, nothing in Postmark Activity" — is
        // obvious at startup.
        log.info("[Postmark] Server token loaded: {} (length {})",
                token != null && !token.isBlank(), token == null ? 0 : token.length());

        HttpClient httpClient = buildHttpClient(
                postmarkProperties.getTimeout().getConnect(),
                postmarkProperties.getTimeout().getRead()
        );

        return WebClient.builder()
                .baseUrl(postmarkProperties.getApi().getBaseUrl())
                .defaultHeader("X-Postmark-Server-Token", token)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logErrorResponse("Postmark"))
                .build();
    }

    private HttpClient buildHttpClient(long connectTimeoutMs, long readTimeoutMs) {
        return HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)
                ));
    }

    /**
     * Logs HTTP 4xx/5xx responses (with the response body) without swallowing
     * them — the caller still sees the WebClientResponseException.
     */
    private ExchangeFilterFunction logErrorResponse(String clientName) {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (response.statusCode().isError()) {
                return response.bodyToMono(String.class)
                        .defaultIfEmpty("[no body]")
                        .flatMap(body -> {
                            log.error("[{}] HTTP {} — {}",
                                    clientName, response.statusCode().value(), body);
                            return Mono.just(response);
                        });
            }
            return Mono.just(response);
        });
    }
}
