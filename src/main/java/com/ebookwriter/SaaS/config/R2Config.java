package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.R2Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Builds the {@link S3Client} pointed at Cloudflare R2.
 *
 * <p>R2 exposes an S3-compatible API, so the standard AWS SDK v2 client works
 * once it is aimed at the account endpoint, told to use path-style addressing
 * (R2 does not do virtual-host buckets) and given the fixed {@code auto} region.
 *
 * <p>Like {@link AnthropicConfig}, the bean is always created — when R2 is not
 * configured it is built with placeholder credentials and a placeholder
 * endpoint so the context still starts; the storage service refuses to touch it
 * until {@link R2Properties#isConfigured()} is true, so a missing config surfaces
 * as a clear "image storage not configured" error rather than a boot failure.
 */
@Slf4j
@Configuration
public class R2Config {

    private static final String PLACEHOLDER = "not-configured";
    private static final String PLACEHOLDER_ENDPOINT = "https://not-configured.r2.cloudflarestorage.com";

    @Bean
    public S3Client r2Client(R2Properties properties) {
        boolean configured = properties.isConfigured();

        String endpoint = configured ? properties.resolveEndpoint() : PLACEHOLDER_ENDPOINT;
        String accessKey = configured ? properties.getAccessKeyId().trim() : PLACEHOLDER;
        String secretKey = configured ? properties.getSecretAccessKey().trim() : PLACEHOLDER;

        if (configured) {
            log.info("R2 storage configured (bucket={}, endpoint={}).",
                    properties.getBucket(), endpoint);
        } else {
            log.warn("R2 storage not configured (r2.access-key-id / r2.secret-access-key / "
                    + "r2.bucket missing). Ebook image upload and image rendering will fail "
                    + "until the R2_* environment variables are set.");
        }

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // R2 ignores the region but the SDK requires one; "auto" is the
                // value Cloudflare documents for the S3 API.
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                // R2 buckets are addressed path-style, not as virtual hosts.
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .httpClientBuilder(ApacheHttpClient.builder())
                .build();
    }
}
