package com.ebookwriter.SaaS.config;

import com.ebookwriter.SaaS.config.properties.R2Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Verifies at startup that the configured R2 bucket actually exists, so a
 * misconfiguration (wrong bucket name, wrong account, or a bucket that was
 * never created) surfaces as a clear boot-log message instead of a cryptic
 * "bucket does not exist" 400 the first time a user uploads an image.
 *
 * <p>Never fails the boot — R2 is optional and the app must start without it —
 * and, when {@code r2.auto-create-bucket=true}, tries to create the bucket if a
 * token with the right permission is configured.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class R2StartupCheck implements ApplicationRunner {

    private final S3Client r2Client;
    private final R2Properties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isConfigured()) {
            return; // not configured — R2Config already logged this
        }
        String bucket = properties.getBucket();
        String endpoint = properties.resolveEndpoint();

        try {
            r2Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("[R2] Bucket '{}' reachable at {}.", bucket, endpoint);
        } catch (NoSuchBucketException e) {
            if (properties.isAutoCreateBucket()) {
                createBucket(bucket, endpoint);
            } else {
                warnMissing(bucket, endpoint);
            }
        } catch (S3Exception e) {
            // 403 etc. — usually a credentials/permission or account-id mismatch.
            log.warn("=================================================================");
            log.warn("[R2] Could not verify bucket '{}' at {}: HTTP {} — {}", bucket, endpoint,
                    e.statusCode(), e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());
            log.warn("[R2] Check R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY and that R2_ACCOUNT_ID "
                    + "matches the account (and token) that owns the bucket.");
            log.warn("=================================================================");
        } catch (RuntimeException e) {
            log.warn("[R2] Bucket check for '{}' failed: {}", bucket, e.getMessage());
        }
    }

    private void createBucket(String bucket, String endpoint) {
        try {
            r2Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("[R2] Created bucket '{}' at {} (R2_AUTO_CREATE_BUCKET=true).", bucket, endpoint);
        } catch (S3Exception e) {
            log.warn("=================================================================");
            log.warn("[R2] Auto-create of bucket '{}' failed (HTTP {}): {}", bucket, e.statusCode(),
                    e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());
            log.warn("[R2] An object-scoped token cannot create buckets — create '{}' in the "
                    + "Cloudflare R2 dashboard instead.", bucket);
            log.warn("=================================================================");
        }
    }

    private void warnMissing(String bucket, String endpoint) {
        log.warn("=================================================================");
        log.warn("[R2] Bucket '{}' does not exist at {}.", bucket, endpoint);
        log.warn("[R2] Image upload/render will fail until it exists. Create a bucket with that "
                + "exact name (lowercase) in Cloudflare R2, or set R2_BUCKET / R2_ACCOUNT_ID to "
                + "match an existing bucket. (Or set R2_AUTO_CREATE_BUCKET=true if the token may "
                + "create buckets.)");
        // Diagnostic: show which buckets this token/endpoint can actually see. If
        // the target bucket exists elsewhere (another account, or a jurisdiction
        // endpoint like <acct>.eu.r2.cloudflarestorage.com), it won't be listed
        // here — set R2_ENDPOINT to the exact endpoint the bucket lives behind.
        logVisibleBuckets();
        log.warn("=================================================================");
    }

    /** Best-effort list of buckets visible to the configured token, for diagnosis. */
    private void logVisibleBuckets() {
        try {
            var visible = r2Client.listBuckets().buckets().stream()
                    .map(software.amazon.awssdk.services.s3.model.Bucket::name)
                    .toList();
            if (visible.isEmpty()) {
                log.warn("[R2] This token/endpoint sees no buckets. Likely a jurisdiction endpoint "
                        + "mismatch (set R2_ENDPOINT to the one your other app uses) or a token in a "
                        + "different account.");
            } else {
                log.warn("[R2] Buckets visible to this token at {}: {}. If your target isn't here, it "
                        + "lives behind a different endpoint/account — set R2_ENDPOINT accordingly.",
                        properties.resolveEndpoint(), visible);
            }
        } catch (S3Exception e) {
            // A bucket-scoped token often can't list — that's fine, it's only a hint.
            log.warn("[R2] Could not list buckets for diagnosis (HTTP {}): the token may be scoped to "
                    + "specific buckets. Ensure R2_BUCKET is one the token can access.", e.statusCode());
        } catch (RuntimeException e) {
            log.warn("[R2] Could not list buckets for diagnosis: {}", e.getMessage());
        }
    }
}
