package com.ebookwriter.SaaS.service.ebook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The backend must trust the bytes, not the client's declared MIME type. These
 * lock the magic-byte sniffing for the accepted formats and the rejection of
 * anything else.
 */
class EbookImageServiceTest {

    @Test
    void detectsPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        assertEquals("image/png", EbookImageService.detectContentType(png));
    }

    @Test
    void detectsJpeg() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        assertEquals("image/jpeg", EbookImageService.detectContentType(jpeg));
    }

    @Test
    void detectsGif() {
        assertEquals("image/gif", EbookImageService.detectContentType(
                "GIF89a....".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void detectsWebp() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertEquals("image/webp", EbookImageService.detectContentType(webp));
    }

    @Test
    void detectsSvg() {
        assertEquals("image/svg+xml", EbookImageService.detectContentType(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8)));
        assertEquals("image/svg+xml", EbookImageService.detectContentType(
                "<?xml version=\"1.0\"?><svg></svg>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsNonImageBytes() {
        assertNull(EbookImageService.detectContentType("not an image".getBytes(StandardCharsets.UTF_8)));
        assertNull(EbookImageService.detectContentType(new byte[]{1, 2}));
        assertNull(EbookImageService.detectContentType(null));
    }
}
