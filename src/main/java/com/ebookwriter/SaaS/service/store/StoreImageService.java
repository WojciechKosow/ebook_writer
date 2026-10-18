package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.Store;
import com.ebookwriter.SaaS.entity.StoreProduct;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.repository.StoreProductRepository;
import com.ebookwriter.SaaS.repository.StoreRepository;
import com.ebookwriter.SaaS.service.ebook.EbookImageService;
import com.ebookwriter.SaaS.service.library.AssetLibraryService;
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
 * file's bytes, not the client. Uploads are kept in the user's asset library
 * too, and a library image can be used here instead of uploading again.
 *
 * <p>Also a product's own cover/thumbnail — kept apart from its downloadable
 * files, under the same rules.
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
    private final StoreProductRepository productRepository;
    private final R2StorageService storage;
    private final AssetLibraryService assetLibraryService;

    @Transactional
    public void upload(User user, Kind kind, MultipartFile file) {
        Store store = requireOwn(user);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the file");
        }
        String type = validate(bytes);
        int[] dimensions = EbookImageService.readDimensions(bytes);
        // Kept in the user's library too, for reuse in books and elsewhere.
        assetLibraryService.remember(user.getId(), bytes, type,
                EbookImageService.sanitizeFilename(file.getOriginalFilename()), dimensions[0], dimensions[1]);
        replace(store, kind, bytes, type);
    }

    /** Use an image from the user's asset library (the store gets its own copy). */
    @Transactional
    public void useFromLibrary(User user, Kind kind, UUID libraryAssetId) {
        Store store = requireOwn(user);
        AssetLibraryService.Loaded loaded = assetLibraryService.use(user.getId(), libraryAssetId);
        replace(store, kind, loaded.bytes(), validate(loaded.bytes()));
    }

    private static String validate(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Images can be up to 3 MB");
        }
        String type = EbookImageService.detectContentType(bytes);
        if (type == null || !ALLOWED.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a PNG, JPG or WebP image");
        }
        return type;
    }

    private void replace(Store store, Kind kind, byte[] bytes, String type) {
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

    // ---- Product cover ---------------------------------------------------------

    /** Upload a product's own cover (replaces the ebook's first page as its cover). */
    @Transactional
    public void uploadProductCover(User user, UUID productId, MultipartFile file) {
        StoreProduct product = requireOwnProduct(user, productId);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the file");
        }
        String type = validate(bytes);
        int[] dimensions = EbookImageService.readDimensions(bytes);
        assetLibraryService.remember(user.getId(), bytes, type,
                EbookImageService.sanitizeFilename(file.getOriginalFilename()), dimensions[0], dimensions[1]);
        replaceProductCover(product, bytes, type);
    }

    @Transactional
    public void useLibraryProductCover(User user, UUID productId, UUID libraryAssetId) {
        StoreProduct product = requireOwnProduct(user, productId);
        AssetLibraryService.Loaded loaded = assetLibraryService.use(user.getId(), libraryAssetId);
        replaceProductCover(product, loaded.bytes(), validate(loaded.bytes()));
    }

    @Transactional
    public void removeProductCover(User user, UUID productId) {
        StoreProduct product = requireOwnProduct(user, productId);
        String old = product.getCoverKey();
        product.setCoverKey(null);
        product.setCoverContentType(null);
        productRepository.save(product);
        deleteQuietly(old);
    }

    /** A product's own cover, if it has one. */
    public Optional<Image> productCover(StoreProduct product) {
        if (product.getCoverKey() == null || product.getCoverContentType() == null) {
            return Optional.empty();
        }
        return storage.download(product.getCoverKey()).map(bytes -> new Image(bytes, product.getCoverContentType()));
    }

    private void replaceProductCover(StoreProduct product, byte[] bytes, String type) {
        String key = "products/" + product.getId() + "/cover-" + UUID.randomUUID() + "." + EXT.get(type);
        storage.upload(key, bytes, type);
        String old = product.getCoverKey();
        product.setCoverKey(key);
        product.setCoverContentType(type);
        productRepository.save(product);
        deleteQuietly(old);
    }

    private StoreProduct requireOwnProduct(User user, UUID productId) {
        Store store = requireOwn(user);
        return productRepository.findByIdAndStoreId(productId, store.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
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
