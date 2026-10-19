package com.ebookwriter.SaaS.service.store;

import com.ebookwriter.SaaS.entity.*;
import com.ebookwriter.SaaS.repository.*;
import com.ebookwriter.SaaS.service.storage.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The downloadable files of store products: the author's uploads (PDF, EPUB,
 * ZIP — stored unmodified in the private R2 bucket) and, for ebook products,
 * the ebook's own current PDF. Also the single place that opens a product's file
 * for delivery, so the guest token download and My Purchases serve the same thing.
 *
 * <p>Files are referenced towards clients by an opaque id: the upload's UUID, or
 * {@link #EBOOK_PDF_ID} for the ebook's PDF. Storage keys are generated here and
 * never leave the backend.
 *
 * <p>Buyers always get the product's <em>current</em> files. Replacing a file
 * keeps the old row as history (see {@link ProductFile}) and deletes the old
 * object only after the transaction commits; a failed upload or a rolled-back
 * transaction deletes the new object again, so storage never holds orphans the
 * database doesn't know about (best effort — a failed delete is only logged).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFileService {

    /** File id of an ebook product's generated PDF. */
    public static final String EBOOK_PDF_ID = "ebook-pdf";

    private final ProductFileRepository fileRepository;
    private final StoreProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final StoreOrderRepository orderRepository;
    private final EbookRepository ebookRepository;
    private final EbookPdfRepository pdfRepository;
    private final R2StorageService storage;

    @Value("${store.files.max-file-bytes:209715200}")
    private long maxFileBytes;

    @Value("${store.files.max-files-per-product:10}")
    private int maxFilesPerProduct;

    // ---- Author --------------------------------------------------------------

    @Transactional
    public ProductFile upload(User user, UUID productId, MultipartFile upload) {
        StoreProduct product = requireOwnProduct(user, productId);
        List<ProductFile> current = current(product.getId());
        if (current.size() >= maxFilesPerProduct) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A product can have up to " + maxFilesPerProduct + " files.");
        }
        ProductFile file = store(user, product, upload);
        file.setSortOrder(current.stream().mapToInt(ProductFile::getSortOrder).max().orElse(-1) + 1);
        file = fileRepository.save(file);
        log.info("[StoreFiles] Product {}: uploaded file {} ({} {}, {} bytes)",
                product.getId(), file.getId(), file.getFormat(), file.getOriginalFilename(), file.getSizeBytes());
        return file;
    }

    /**
     * Replace a file with a new upload. Buyers get the new version from now on;
     * the old row stays as history and its object is deleted after commit.
     */
    @Transactional
    public ProductFile replace(User user, UUID productId, UUID fileId, MultipartFile upload) {
        StoreProduct product = requireOwnProduct(user, productId);
        ProductFile old = fileRepository.findByIdAndProductIdAndRemovedAtIsNull(fileId, product.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        ProductFile file = store(user, product, upload);
        file.setVersion(old.getVersion() + 1);
        file.setReplacesId(old.getId());
        file.setSortOrder(old.getSortOrder());
        // Same extra, new bytes: the name buyers see, its note and who gets it stay.
        file.setLabel(old.getLabel());
        file.setNote(old.getNote());
        file.setAccountOnly(old.isAccountOnly());
        old.setRemovedAt(LocalDateTime.now());
        old.setRemovedReason("REPLACED");
        fileRepository.save(old);
        file = fileRepository.save(file);
        deleteAfterCommit(old.getStorageKey());
        log.info("[StoreFiles] Product {}: file {} replaced by {} (v{})",
                product.getId(), old.getId(), file.getId(), file.getVersion());
        return file;
    }

    /**
     * Remove a file. The last file of a product that buyers own can't be removed
     * (it would leave them with nothing) — it can only be replaced. A product left
     * with nothing to deliver is hidden from the store.
     */
    @Transactional
    public void remove(User user, UUID productId, UUID fileId) {
        StoreProduct product = requireOwnProduct(user, productId);
        ProductFile file = fileRepository.findByIdAndProductIdAndRemovedAtIsNull(fileId, product.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
        boolean last = current(product.getId()).size() == 1 && !hasEbookPdf(product);
        if (last && orderRepository.existsByProductIdAndStatus(product.getId(), StoreOrderStatus.PAID)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Buyers own this product, so its last file can't be removed. Replace it instead.");
        }
        file.setRemovedAt(LocalDateTime.now());
        file.setRemovedReason("REMOVED");
        fileRepository.save(file);
        if (last && product.isActive()) {
            product.setActive(false);
            productRepository.save(product);
        }
        deleteAfterCommit(file.getStorageKey());
        log.info("[StoreFiles] Product {}: removed file {}", product.getId(), file.getId());
    }

    /** Save a file's details (the store's publish applies the draft's names and perks). */
    @Transactional
    public ProductFile save(ProductFile file) {
        return fileRepository.save(file);
    }

    /** Current uploaded files of a product, in display order. */
    @Transactional(readOnly = true)
    public List<ProductFile> current(UUID productId) {
        return fileRepository.findByProductIdAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(productId);
    }

    /** Current uploaded files of several products, grouped by product. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ProductFile>> currentByProduct(Collection<UUID> productIds) {
        Map<UUID, List<ProductFile>> map = new HashMap<>();
        if (productIds.isEmpty()) {
            return map;
        }
        for (ProductFile f : fileRepository.findByProductIdInAndRemovedAtIsNullOrderBySortOrderAscCreatedAtAsc(productIds)) {
            map.computeIfAbsent(f.getProductId(), k -> new ArrayList<>()).add(f);
        }
        return map;
    }

    // ---- Delivery ------------------------------------------------------------

    /** Whether the product has anything to deliver (checkout requires it). */
    @Transactional(readOnly = true)
    public boolean isDeliverable(StoreProduct product) {
        return hasEbookPdf(product) || fileRepository.countByProductIdAndRemovedAtIsNull(product.getId()) > 0;
    }

    /**
     * What a buyer with a Scrivetta account gets right now: the ebook's PDF (if
     * any), then the uploaded files — account perks included.
     */
    @Transactional(readOnly = true)
    public List<DeliverableFile> deliverables(StoreProduct product) {
        return deliverables(product, true);
    }

    /** What a buyer gets; {@code withPerks} false = a guest (the emailed link), without the account perks. */
    @Transactional(readOnly = true)
    public List<DeliverableFile> deliverables(StoreProduct product, boolean withPerks) {
        List<DeliverableFile> files = new ArrayList<>();
        if (product.getEbookId() != null) {
            pdfRepository.findById(product.getEbookId()).ifPresent(pdf -> files.add(new DeliverableFile(
                    EBOOK_PDF_ID, ebookFilename(product), ProductFileFormat.PDF, pdf.getData().length, 1,
                    ebookRepository.findById(product.getEbookId()).map(Ebook::getUpdatedAt).orElse(null),
                    null, null, false)));
        }
        for (ProductFile f : current(product.getId())) {
            if (withPerks || !f.isAccountOnly()) {
                files.add(new DeliverableFile(f.getId().toString(), f.getOriginalFilename(), f.getFormat(),
                        f.getSizeBytes(), f.getVersion(), f.getCreatedAt(), f.getLabel(), f.getNote(), f.isAccountOnly()));
            }
        }
        return files;
    }

    /** The account perks a guest doesn't get (shown to them as "comes with a free account"). */
    @Transactional(readOnly = true)
    public List<DeliverableFile> perks(StoreProduct product) {
        return deliverables(product, true).stream().filter(DeliverableFile::accountOnly).toList();
    }

    /**
     * Open one of the product's current files for delivery. 404 for an id that
     * isn't one of the product's current files; 410 when the bytes are gone.
     * Authorisation is the caller's job.
     */
    @Transactional(readOnly = true)
    public FileStream open(StoreProduct product, String fileId) {
        if (EBOOK_PDF_ID.equals(fileId)) {
            if (product.getEbookId() == null) {
                throw fileNotFound();
            }
            EbookPdf pdf = pdfRepository.findById(product.getEbookId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This file is no longer available."));
            return new FileStream(new ByteArrayInputStream(pdf.getData()), pdf.getData().length,
                    ebookFilename(product), ProductFileFormat.PDF.contentType());
        }
        UUID id;
        try {
            id = UUID.fromString(fileId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw fileNotFound();
        }
        ProductFile file = fileRepository.findByIdAndProductIdAndRemovedAtIsNull(id, product.getId())
                .orElseThrow(this::fileNotFound);
        Optional<R2StorageService.StoredObject> object;
        try {
            object = storage.openStream(file.getStorageKey());
        } catch (R2StorageService.StorageException e) {
            log.error("[StoreFiles] Could not open file {} of product {}: {}", file.getId(), product.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Downloads are temporarily unavailable. Please try again in a moment.");
        }
        R2StorageService.StoredObject stored = object.orElseThrow(() -> {
            log.error("[StoreFiles] File {} of product {} is missing from storage", file.getId(), product.getId());
            return new ResponseStatusException(HttpStatus.GONE,
                    "This file is temporarily unavailable. Please contact the author.");
        });
        return new FileStream(stored.stream(), stored.length() >= 0 ? stored.length() : file.getSizeBytes(),
                file.getOriginalFilename(), file.getFormat().contentType());
    }

    /** The primary file (the first file a guest gets) — for the original single-file download link. */
    @Transactional(readOnly = true)
    public FileStream openPrimary(StoreProduct product) {
        List<DeliverableFile> files = deliverables(product, false);
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "This file is no longer available.");
        }
        return open(product, files.get(0).id());
    }

    // ------------------------------------------------------------------------

    private boolean hasEbookPdf(StoreProduct product) {
        return product.getEbookId() != null && pdfRepository.existsById(product.getEbookId());
    }

    /** Validate, hash and upload; returns an unsaved row. Never trusts the client's name or type. */
    private ProductFile store(User user, StoreProduct product, MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty.");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("product-file-", ".upload");
            upload.transferTo(tmp);
            long size = Files.size(tmp);
            ProductFileFormat format = ProductFileValidator.validate(upload.getOriginalFilename(), tmp, size, maxFileBytes);
            String key = "products/" + product.getId() + "/files/" + UUID.randomUUID() + "." + format.extension();
            String sha256 = sha256(tmp);
            try {
                storage.upload(key, tmp, size, format.contentType());
            } catch (R2StorageService.StorageException e) {
                log.error("[StoreFiles] Upload to storage failed for product {}: {}", product.getId(), e.getMessage());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not store the file right now. Please try again.");
            }
            deleteOnRollback(key);

            ProductFile file = new ProductFile();
            file.setProductId(product.getId());
            file.setStoreId(product.getStoreId());
            file.setOriginalFilename(ProductFileValidator.sanitizeFilename(upload.getOriginalFilename(), format));
            file.setFormat(format);
            file.setContentType(format.contentType());
            file.setSizeBytes(size);
            file.setSha256(sha256);
            file.setStorageKey(key);
            file.setUploadedBy(user.getId());
            return file;
        } catch (IOException e) {
            log.warn("[StoreFiles] Could not read upload for product {}: {}", product.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the file. Please try again.");
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    log.warn("[StoreFiles] Could not delete temp file {}: {}", tmp, e.getMessage());
                }
            }
        }
    }

    private StoreProduct requireOwnProduct(User user, UUID productId) {
        Store store = storeRepository.findByOwnerId(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You don't have a store yet"));
        return productRepository.findByIdAndStoreId(productId, store.getId())
                .filter(p -> p.getRemovedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private String ebookFilename(StoreProduct product) {
        String title = ebookRepository.findById(product.getEbookId()).map(Ebook::getTitle).orElse(product.getTitle());
        String base = title != null && !title.isBlank() ? title : "ebook";
        String cleaned = base.replaceAll("[^a-zA-Z0-9-_ ]", "").trim().replaceAll("\\s+", "_");
        return (cleaned.isBlank() ? "ebook" : cleaned) + ".pdf";
    }

    private void deleteAfterCommit(String key) {
        runAfter(true, key);
    }

    private void deleteOnRollback(String key) {
        runAfter(false, key);
    }

    private void runAfter(boolean onCommit, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (onCommit) {
                deleteQuietly(key);
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if ((status == STATUS_COMMITTED) == onCommit) {
                    deleteQuietly(key);
                }
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("[StoreFiles] Could not delete object {}: {}", key, e.getMessage());
        }
    }

    private ResponseStatusException fileNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            byte[] buf = new byte[64 * 1024];
            while (in.read(buf) != -1) {
                // digesting
            }
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A file a buyer can download, as listed to them. */
    public record DeliverableFile(String id, String filename, ProductFileFormat format, long sizeBytes,
                                  int version, LocalDateTime updatedAt, String label, String note,
                                  boolean accountOnly) {
    }

    /** An opened file: the caller streams and closes it. */
    public record FileStream(InputStream stream, long length, String filename, String contentType) {
    }
}
