package com.ebookwriter.SaaS.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cloudflare R2 object-storage configuration. R2 speaks the S3 API, so we talk
 * to it with the AWS SDK v2 pointed at the account-scoped R2 endpoint.
 *
 * <p>The bucket is <b>private</b>: the app uploads with the access keys and, at
 * PDF render time, streams the image bytes straight back into the document (the
 * same way the bundled fonts are embedded). Nothing is served from a public
 * bucket URL. Set the {@code R2_*} environment variables to enable it; with them
 * blank the app still boots and only image upload/render fails with a clear
 * message (mirrors the Anthropic/Stripe placeholders).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "r2")
public class R2Properties {

    /** Cloudflare account id — used to derive the endpoint when one isn't set. */
    private String accountId;

    /** R2 access key id (R2 API token). Set R2_ACCESS_KEY_ID. */
    private String accessKeyId;

    /** R2 secret access key. Set R2_SECRET_ACCESS_KEY. */
    private String secretAccessKey;

    /** Bucket that holds ebook images. Set R2_BUCKET. */
    private String bucket;

    /**
     * S3 API endpoint. Blank = derived from {@link #accountId} as
     * {@code https://<accountId>.r2.cloudflarestorage.com}. Override with
     * R2_ENDPOINT only if you use a jurisdiction-specific endpoint.
     */
    private String endpoint;

    /**
     * When true, create the configured bucket at startup if it doesn't exist.
     * Requires an R2 token with bucket-create permission (Admin Read &amp; Write);
     * an object-scoped token cannot create buckets, so leave this false and
     * create the bucket in the dashboard. Default false. Set R2_AUTO_CREATE_BUCKET.
     */
    private boolean autoCreateBucket = false;

    /** True once the credentials + bucket needed to talk to R2 are all present. */
    public boolean isConfigured() {
        return isNotBlank(accessKeyId)
                && isNotBlank(secretAccessKey)
                && isNotBlank(bucket)
                && isNotBlank(resolveEndpoint());
    }

    /** The effective endpoint: the explicit override, else derived from the account id. */
    public String resolveEndpoint() {
        if (isNotBlank(endpoint)) {
            return endpoint.trim();
        }
        if (isNotBlank(accountId)) {
            return "https://" + accountId.trim() + ".r2.cloudflarestorage.com";
        }
        return null;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
