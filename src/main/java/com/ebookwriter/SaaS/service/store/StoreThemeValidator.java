package com.ebookwriter.SaaS.service.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a store page design before it is saved. The document is rendered on
 * the public store, so: it must be a JSON object naming a known style, and the
 * author's custom CSS may not load anything from elsewhere or break out of its
 * {@code <style>} element. Texts need no checks here — the frontend renders
 * them as plain text, never as HTML, and designs carry no links of their own.
 */
public final class StoreThemeValidator {

    public static final Set<String> STYLES = Set.of("literary", "code", "minimal", "modern", "bold");
    static final int MAX_CSS_LENGTH = 20_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Fetches (url, @import, image-set), old IE script hooks and anything that could close the style element. */
    private static final Pattern FORBIDDEN_CSS = Pattern.compile(
            "@import|url\\s*\\(|image-set|image\\s*\\(|expression\\s*\\(|javascript:|behavior\\s*:|-moz-binding|<|\\\\");

    private StoreThemeValidator() {
    }

    /** The document to store, or null for the default (Scrivetta) style. */
    public static String validate(String settings) {
        if (settings == null || settings.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(settings);
        } catch (JsonProcessingException e) {
            throw bad("This design could not be read.");
        }
        if (root == null || !root.isObject()) {
            throw bad("This design could not be read.");
        }
        JsonNode active = root.get("active");
        if (active == null || !active.isTextual() || !STYLES.contains(active.asText())) {
            throw bad("Choose one of the available styles.");
        }
        for (JsonNode css : root.findValues("customCss")) {
            if (css.isNull()) {
                continue;
            }
            if (!css.isTextual()) {
                throw bad("Custom CSS could not be read.");
            }
            String text = css.asText();
            if (text.length() > MAX_CSS_LENGTH) {
                throw bad("Custom CSS is too long (max " + MAX_CSS_LENGTH + " characters).");
            }
            if (FORBIDDEN_CSS.matcher(text.toLowerCase(Locale.ROOT)).find()) {
                throw bad("Custom CSS can't load files or other pages (url(), @import), use \"<\" or backslashes.");
            }
        }
        return settings;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
