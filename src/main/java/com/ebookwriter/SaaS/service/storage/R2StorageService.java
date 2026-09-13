package com.ebookwriter.SaaS.service.storage;

import com.ebookwriter.SaaS.config.properties.R2Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Optional;

/**
 * Thin wrapper over the R2 (S3) client for the object operations the ebook
 * image feature needs: put, get, delete. Keys are opaque strings chosen by the
 * caller (see {@code EbookImageService}); this class only moves bytes.
 *
 * <p>Every mutating call first checks {@link R2Properties#isConfigured()} so an
 * unconfigured deployment fails with a clear, actionable message instead of an
 * opaque SDK/credentials error.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class R2StorageService {

    private final S3Client r2Client;
    private final R2Properties properties;

    /** True when R2 credentials + bucket are present and storage is usable. */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Upload bytes under {@code key} with the given content type. Overwrites any
     * existing object at that key.
     */
    public void upload(String key, byte[] data, String contentType) {
        requireConfigured();
        try {
            r2Client.putObject(PutObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .contentType(contentType)
                            .contentLength((long) data.length)
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (NoSuchBucketException e) {
            throw new StorageException(bucketMissingMessage(), e);
        } catch (S3Exception e) {
            throw new StorageException("Failed to upload object " + key + ": "
                    + e.awsErrorDetails().errorMessage(), e);
        }
    }

    /** Download the object at {@code key}, or empty if it does not exist. */
    public Optional<byte[]> download(String key) {
        requireConfigured();
        try {
            ResponseBytes<GetObjectResponse> object = r2Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(key)
                            .build());
            return Optional.of(object.asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            throw new StorageException("Failed to download object " + key + ": "
                    + e.awsErrorDetails().errorMessage(), e);
        }
    }

    /** Delete the object at {@code key}. Missing objects are a no-op. */
    public void delete(String key) {
        requireConfigured();
        try {
            r2Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (S3Exception e) {
            // A failed delete leaves an orphaned object but must not break the
            // user-facing operation (e.g. removing an image row); log and move on.
            log.warn("Failed to delete R2 object {}: {}", key, e.awsErrorDetails().errorMessage());
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new StorageException("Image storage is not configured. Set the R2_ACCESS_KEY_ID, "
                    + "R2_SECRET_ACCESS_KEY, R2_BUCKET and R2_ACCOUNT_ID environment variables.");
        }
    }

    /** Actionable message for the common "bucket doesn't exist" misconfiguration. */
    private String bucketMissingMessage() {
        return "Image storage bucket '" + properties.getBucket() + "' was not found at "
                + properties.resolveEndpoint() + ". Create a bucket with that exact name "
                + "(lowercase) in Cloudflare R2, or fix R2_BUCKET / R2_ACCOUNT_ID to match an "
                + "existing bucket. (You can also set R2_AUTO_CREATE_BUCKET=true if the R2 token "
                + "may create buckets.)";
    }

    /** Thrown when an object-storage operation cannot be completed. */
    public static class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
