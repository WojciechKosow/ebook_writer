package com.ebookwriter.SaaS.service.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store's unpublished changes ({@code Store.draft}): one document for the
 * whole store, so "Publish changes" applies everything at once. Only what
 * differs from the live store is kept — {@link StoreService} prunes anything
 * that matches it again, so the draft's size is the number of real changes.
 *
 * <p>Text fields use {@code ""} for "cleared" and null for "unchanged".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoreDraft {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Brand and about you, as a whole; null = unchanged. */
    public Profile profile;

    /** The store design (JSON); "" = back to the default style; null = unchanged. */
    public String theme;

    /** Every product's position (ids, first = featured); null = unchanged. */
    public List<UUID> order;

    public Map<UUID, ProductChanges> products = new LinkedHashMap<>();

    public Map<UUID, FileChanges> files = new LinkedHashMap<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Profile {
        public String name;
        public String description;
        public String headline;
        public String authorName;
        public String contactEmail;
        public String instagramUrl;
        public String tiktokUrl;
        public String websiteUrl;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductChanges {
        public String title;
        public String description;
        public String highlights;
        public Long priceCents;
        public Boolean active;
        public String pageSettings;

        boolean isEmpty() {
            return title == null && description == null && highlights == null && priceCents == null
                    && active == null && pageSettings == null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileChanges {
        public String label;
        public String note;
        public Boolean accountOnly;

        boolean isEmpty() {
            return label == null && note == null && accountOnly == null;
        }
    }

    boolean isEmpty() {
        return profile == null && theme == null && order == null && products.isEmpty() && files.isEmpty();
    }

    static StoreDraft parse(String json) {
        if (json == null || json.isBlank()) {
            return new StoreDraft();
        }
        try {
            StoreDraft draft = MAPPER.readValue(json, StoreDraft.class);
            if (draft.products == null) {
                draft.products = new LinkedHashMap<>();
            }
            if (draft.files == null) {
                draft.files = new LinkedHashMap<>();
            }
            return draft;
        } catch (JsonProcessingException e) {
            // A draft we can't read is dropped rather than blocking the store.
            return new StoreDraft();
        }
    }

    /** Null when there's nothing pending. */
    String toJson() {
        if (isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
