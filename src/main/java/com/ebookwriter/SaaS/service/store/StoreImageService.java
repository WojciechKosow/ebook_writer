package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.Store;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.service.ebook.EbookImageService;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The store's logo and author photo. Stored in the private R2 bucket and served
 * through the public store API. Only raster formats (PNG, JPEG, WebP) are
 * accepted — never SVG, which can carry script — and the type is taken from the
 * file's bytes, not the client.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreImageService {

    public enum Kind { LOGO, PHOTO }

    static final long MAX_BYTES = 3L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Map<String, String> EXT = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");

    private final StoreRepository storeRepository;
    private final R2StorageService storage;

    @Transactional
    public void upload(User user, Kind kind, MultipartFile file) {
        Store store = requireOwn(user);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the file");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Images can be up to 3 MB");
        }
        String type = EbookImageService.detectContentType(bytes);
        if (type == null || !ALLOWED.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a PNG, JPG or WebP image");
        }
        String key = "stores/" + store.getId() + "/" + kind.name().toLowerCase() + "-" + UUID.randomUUID()
                + "." + EXT.get(type);
        storage.upload(key, bytes, type);
        String old = kind == Kind.LOGO ? store.getLogoKey() : store.getPhotoKey();
        if (kind == Kind.LOGO) {
            store.setLogoKey(key);
            store.setLogoContentType(type);
        } else {
            store.setPhotoKey(key);
            store.setPhotoContentType(type);
        }
        storeRepository.save(store);
        deleteQuietly(old);
    }

    @Transactional
    public void remove(User user, Kind kind) {
        Store store = requireOwn(user);
        String old = kind == Kind.LOGO ? store.getLogoKey() : store.getPhotoKey();
        if (kind == Kind.LOGO) {
            store.setLogoKey(null);
            store.setLogoContentType(null);
        } else {
            store.setPhotoKey(null);
            store.setPhotoContentType(null);
        }
        storeRepository.save(store);
        deleteQuietly(old);
    }

    /** The image of a store, for the public store page. */
    @Transactional(readOnly = true)
    public Optional<Image> get(String slug, Kind kind) {
        return storeRepository.findBySlug(slug).flatMap(store -> {
            String key = kind == Kind.LOGO ? store.getLogoKey() : store.getPhotoKey();
            String type = kind == Kind.LOGO ? store.getLogoContentType() : store.getPhotoContentType();
            if (key == null || type == null) {
                return Optional.empty();
            }
            return storage.download(key).map(bytes -> new Image(bytes, type));
        });
    }

    /** Relative public URL of a store image (versioned by its key), or null. */
    public static String url(Store store, Kind kind) {
        String key = kind == Kind.LOGO ? store.getLogoKey() : store.getPhotoKey();
        if (key == null) {
            return null;
        }
        String version = Integer.toHexString(key.hashCode());
        return "/api/public/stores/" + store.getSlug() + "/images/" + kind.name().toLowerCase() + "?v=" + version;
    }

    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("[Store] Could not delete old image {}: {}", key, e.getMessage());
        }
    }

    private Store requireOwn(User user) {
        return storeRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You don't have a store yet"));
    }

    public record Image(byte[] bytes, String contentType) {
    }
}
