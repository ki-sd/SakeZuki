package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageFailure;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import com.sakezuki.imagecore.VerifiedImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class ImageAssetService {
    public enum Source { SAKE_GUIDE, USER_SUBMISSION, ADMIN, BREWERY_OFFICIAL }

    private final ImageAssetRepository assets;
    private final ImageStores stores;
    private final TransactionTemplate transaction;
    private final Semaphore processing = new Semaphore(1);

    public ImageAssetService(ImageAssetRepository assets, ImageStores stores, PlatformTransactionManager manager) {
        this.assets = assets;
        this.stores = stores;
        transaction = new TransactionTemplate(manager);
    }

    // The caller supplies a durable asset ID for retries and a server-owned evidence reference.
    public void stage(UUID id, Source source, String evidence, String mime, byte[] bytes) throws IOException, ImageFailure {
        Objects.requireNonNull(id);
        requireRef(evidence, 500);
        enterProcessing();
        try {
            var storage = storage(ImageStores.Area.STAGING);
            var output = ImageTransform.convert(mime, bytes, Set.of());
            var asset = new ImageAssetRepository.Asset(source.name(), evidence, output.original(),
                    output.transformed(), ImageTransform.VERSION);
            var work = transaction.execute(status -> {
                assets.ensureAsset(id, asset);
                return prepare(id, ImageStores.Area.STAGING, asset);
            });
            execute(work, () -> VerifiedImageStorage.store(storage, work.key(), output));
        } finally {
            processing.release();
        }
    }

    // Copies the staged transformed bytes without re-encoding. Publication is a separate DB operation.
    public void prepareDelivery(UUID id) throws IOException, ImageFailure {
        enterProcessing();
        try {
            var staging = storage(ImageStores.Area.STAGING);
            var delivery = storage(ImageStores.Area.DELIVERY);
            var source = transaction.execute(status -> {
                var asset = assets.lockAsset(id);
                assets.requireGrant(id);
                var staged = existing(id, ImageStores.Area.STAGING, asset);
                if (!"VERIFIED".equals(staged.object().state())) {
                    throw new IllegalStateException("verified staging object required");
                }
                return staged;
            });
            VerifiedImageStorage.Copy copy;
            try {
                copy = VerifiedImageStorage.readForCopy(staging, source.key(), source.media());
            } catch (IOException | ImageFailure failure) {
                recordFailure(source, failure);
                throw failure;
            }
            var destination = transaction.execute(status -> {
                var asset = assets.lockAsset(id);
                assets.requireGrant(id);
                return prepare(id, ImageStores.Area.DELIVERY, asset);
            });
            execute(destination, () -> VerifiedImageStorage.storeCopy(delivery, copy));
        } finally {
            processing.release();
        }
    }

    // A trusted operator may revalidate restored content. This path never puts or deletes an object.
    public void revalidate(UUID id, ImageStores.Area area) throws IOException, ImageFailure {
        enterProcessing();
        try {
            var storage = storage(area);
            var work = transaction.execute(status -> {
                var asset = assets.lockAsset(id);
                if (area == ImageStores.Area.DELIVERY) assets.requireGrant(id);
                var original = existing(id, area, asset);
                if (!"PERMANENT_FAILURE".equals(original.object().state())) {
                    throw new IllegalStateException("quarantined object required");
                }
                return new Work(assets.begin(original.object(), true), original.key(), original.media());
            });
            execute(work, () -> VerifiedImageStorage.readForCopy(storage, work.key(), work.media()));
        } finally {
            processing.release();
        }
    }

    private Work prepare(UUID id, ImageStores.Area area, ImageAssetRepository.Asset asset) {
        var key = ImageStorage.ObjectKey.forAsset(id, asset.version(), asset.transformed());
        assets.ensureObject(id, area, key.value(), asset.version());
        var work = existing(id, area, asset);
        if ("PERMANENT_FAILURE".equals(work.object().state())) {
            throw new IllegalStateException("image object requires investigation");
        }
        return new Work(assets.begin(work.object(), false), work.key(), work.media());
    }

    private Work existing(UUID id, ImageStores.Area area, ImageAssetRepository.Asset asset) {
        var key = ImageStorage.ObjectKey.forAsset(id, asset.version(), asset.transformed());
        var object = assets.object(id, area);
        if (!object.key().equals(key.value()) || !object.version().equals(asset.version())) {
            throw new IllegalStateException("image object intent conflict");
        }
        return new Work(object, key, asset.transformed());
    }

    private void execute(Work work, Transfer transfer) throws IOException, ImageFailure {
        try {
            transfer.run();
        } catch (IOException | ImageFailure failure) {
            recordFailure(work, failure);
            throw failure;
        }
        if (!Boolean.TRUE.equals(transaction.execute(status -> assets.verified(work.object(), work.media())))) {
            throw new IOException("image object attempt superseded");
        }
    }

    private void recordFailure(Work work, Exception failure) {
        var object = work.object();
        if ("PERMANENT_FAILURE".equals(object.state())) return;
        boolean integrity = definitiveDamage(failure);
        boolean missing = failure instanceof ImageStorage.ObjectMissing;
        if ("VERIFIED".equals(object.state()) && !integrity && !missing) return;
        try {
            transaction.executeWithoutResult(status -> assets.failed(object.id(), object.attempt(), object.state(),
                    integrity, failureCode(integrity, missing)));
        } catch (RuntimeException updateFailure) {
            failure.addSuppressed(updateFailure);
        }
    }

    void reportPublicReadFailure(ImageAssetRepository.PublicImage image, IOException failure) {
        boolean integrity = definitiveDamage(failure);
        boolean missing = failure instanceof ImageStorage.ObjectMissing;
        if (integrity || missing) {
            assets.failed(image.objectId(), image.generation(), "VERIFIED", integrity, failureCode(integrity, missing));
        }
    }

    private static boolean definitiveDamage(Exception failure) {
        return failure instanceof ImageStorage.IntegrityMismatch || failure instanceof ImageStorage.ReadLimitExceeded
                || failure instanceof ImageFailure imageFailure && (imageFailure.category() == ImageFailure.Category.INVALID_IMAGE
                || imageFailure.category() == ImageFailure.Category.STORAGE_FAILED);
    }

    private static String failureCode(boolean integrity, boolean missing) {
        if (integrity) return "integrity_or_payload_invalid";
        return missing ? "object_missing" : "storage_unavailable";
    }

    private ImageStorage.Readable storage(ImageStores.Area area) throws IOException {
        return stores.storage(area).orElseThrow(() -> new IOException("image storage not configured"));
    }

    private void enterProcessing() throws IOException {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("image storage requests must run outside database transactions");
        }
        if (!processing.tryAcquire()) throw new IOException("image processing busy");
    }

    static void requireRef(String reference, int maxLength) {
        if (reference == null || reference.isBlank() || reference.length() > maxLength
                || reference.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid image evidence or actor reference");
        }
    }

    private record Work(ImageAssetRepository.Stored object, ImageStorage.ObjectKey key, ImageTransform.Info media) {}

    @FunctionalInterface
    private interface Transfer {
        void run() throws IOException, ImageFailure;
    }
}
