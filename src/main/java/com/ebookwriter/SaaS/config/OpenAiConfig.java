package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.OpenAiProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds the WebClient used to talk to the OpenAI Image API. Same reactive
 * Reactor-Netty setup as {@link WebClientConfig} (explicit connect/read
 * timeouts), sized for image generation which can take tens of seconds.
 *
 * <p>Image payloads (base64 PNG) are large, so the in-memory codec limit is
 * raised well above the WebClient default. Like the Anthropic client, the bean
 * is always created (with a placeholder Authorization header when no key is set)
 * so the application boots without OpenAI configured — only image generation is
 * skipped until a key is present.
 */
@Slf4j
@Configuration
public class OpenAiConfig {

    private static final int MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024; // 32 MB (base64 images)

    @Bean(name = "openAiWebClient")
    public WebClient openAiWebClient(OpenAiProperties properties) {
        String key = properties.getApiKey() == null ? "" : properties.getApiKey().trim();
        boolean present = !key.isBlank();
        if (present) {
            log.info("OpenAI API key detected (length={}, imageModel={}).",
                    key.length(), properties.getImageModel());
        } else {
            log.warn("No OpenAI API key configured (openai.api-key / OPENAI_API_KEY is empty). "
                    + "AI image generation is skipped; books are produced text-only until a key is set.");
        }

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (present ? key : "not-configured"))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();
    }
}
