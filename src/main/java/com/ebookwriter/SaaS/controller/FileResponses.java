package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.service.store.ProductFileService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** The one way a store product file is sent to a buyer: a streamed, uncached attachment. */
final class FileResponses {

    private FileResponses() {
    }

    static ResponseEntity<Resource> attachment(ProductFileService.FileStream file) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                // RFC 6266 / 5987 encoding: the author's filename can't break the header.
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()));
        if (file.length() >= 0) {
            builder.contentLength(file.length());
        }
        return builder.body(new InputStreamResource(file.stream()));
    }
}
