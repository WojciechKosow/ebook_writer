package com.ebookwriter.SaaS.service.image;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Response handling for the OpenAI Image API. No network: the pure decoder is
 * exercised against representative success and failure bodies.
 */
class OpenAiImageClientTest {

    @Test
    void decodesBase64ImageFromData() {
        byte[] pixels = {1, 2, 3, 4, 5};
        String b64 = Base64.getEncoder().encodeToString(pixels);
        String body = "{\"data\":[{\"b64_json\":\"" + b64 + "\"}]}";

        assertArrayEquals(pixels, OpenAiImageClient.decodeFirstImage(body));
    }

    @Test
    void surfacesApiErrorMessage() {
        String body = "{\"error\":{\"message\":\"Your prompt was rejected by the safety system\"}}";
        ImageGenerationException ex = assertThrows(ImageGenerationException.class,
                () -> OpenAiImageClient.decodeFirstImage(body));
        assertTrue(ex.getMessage().contains("safety system"));
    }

    @Test
    void rejectsResponseWithoutData() {
        assertThrows(ImageGenerationException.class,
                () -> OpenAiImageClient.decodeFirstImage("{\"data\":[]}"));
    }

    @Test
    void rejectsResponseWithoutBase64() {
        // e.g. a model that returned a URL instead of b64_json.
        String body = "{\"data\":[{\"url\":\"https://example.com/img.png\"}]}";
        assertThrows(ImageGenerationException.class,
                () -> OpenAiImageClient.decodeFirstImage(body));
    }

    @Test
    void rejectsEmptyOrUnparseableBody() {
        assertThrows(ImageGenerationException.class, () -> OpenAiImageClient.decodeFirstImage(""));
        assertThrows(ImageGenerationException.class, () -> OpenAiImageClient.decodeFirstImage(null));
        assertThrows(ImageGenerationException.class, () -> OpenAiImageClient.decodeFirstImage("not json"));
    }
}
