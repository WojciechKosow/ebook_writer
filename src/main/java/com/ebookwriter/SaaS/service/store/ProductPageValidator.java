package com.ebookwriter.SaaS.service.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks a product page design ({@code StoreProduct.pageSettings}) before it is
 * saved. Like the store design it is a JSON document only the frontend
 * interprets, and its texts are rendered as plain text. The one part the
 * backend acts on is {@code preview.chapters}: the chapters buyers may read for
 * free before buying — at most {@value #MAX_SAMPLE_CHAPTERS}, and only chapters
 * the ebook really has (with text).
 */
public final class ProductPageValidator {

    public static final int MAX_LENGTH = 30_000;
    public static final int MAX_SAMPLE_CHAPTERS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProductPageValidator() {
    }

    /** The document to store (null = defaults). {@code chapters}: the ebook's chapter numbers that have text. */
    public static String validate(String settings, Set<Integer> chapters) {
        if (settings == null || settings.isBlank()) {
            return null;
        }
        if (settings.length() > MAX_LENGTH) {
            throw bad("This page design is too large.");
        }
        JsonNode root = read(settings);
        if (root == null || !root.isObject()) {
            throw bad("This page design could not be read.");
        }
        JsonNode sections = root.get("sections");
        if (sections != null && !sections.isNull() && !sections.isObject()) {
            throw bad("This page design could not be read.");
        }
        List<Integer> sample = sampleChapters(root);
        if (sample.size() > MAX_SAMPLE_CHAPTERS) {
            throw bad("Pick up to " + MAX_SAMPLE_CHAPTERS + " chapters for the free preview.");
        }
        for (int n : sample) {
            if (!chapters.contains(n)) {
                throw bad("Chapter " + n + " can't be part of the free preview.");
            }
        }
        return settings;
    }

    /** The free-preview chapter numbers of a stored document (empty when none or unreadable). */
    public static List<Integer> sampleChapters(String settings) {
        if (settings == null || settings.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = read(settings);
            return root != null && root.isObject() ? sampleChapters(root) : List.of();
        } catch (ResponseStatusException e) {
            return List.of();
        }
    }

    private static List<Integer> sampleChapters(JsonNode root) {
        JsonNode preview = root.get("preview");
        if (preview == null || preview.isNull()) {
            return List.of();
        }
        JsonNode list = preview.get("chapters");
        if (!preview.isObject() || (list != null && !list.isNull() && !list.isArray())) {
            throw bad("The free preview could not be read.");
        }
        Set<Integer> out = new LinkedHashSet<>();
        if (list != null) {
            for (JsonNode n : list) {
                if (!n.canConvertToInt() || !n.isIntegralNumber()) {
                    throw bad("The free preview could not be read.");
                }
                out.add(n.asInt());
            }
        }
        return new ArrayList<>(out);
    }

    private static JsonNode read(String settings) {
        try {
            return MAPPER.readTree(settings);
        } catch (JsonProcessingException e) {
            throw bad("This page design could not be read.");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
