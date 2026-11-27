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
            var work = transaction.execute(status -> {
                var asset = assets.lockAsset(id);
                assets.requireGrant(id);
                var staged = assets.object(id, ImageStores.Area.STAGING);
                var next = prepare(id, ImageStores.Area.DELIVERY, asset);
                if (!staged.state().equals("VERIFIED") || !staged.key().equals(next.key().value())
                        || !staged.version().equals(asset.version())) {
                    throw new IllegalStateException("verified staging object required");
                }
                return next;
            });
            execute(work, () -> VerifiedImageStorage.copyVerified(staging, delivery, work.key(), work.media()));
        } finally {
            processing.release();
        }
    }

    private Work prepare(UUID id, ImageStores.Area area, ImageAssetRepository.Asset asset) {
        var key = ImageStorage.ObjectKey.forAsset(id, asset.version(), asset.transformed());
        assets.ensureObject(id, area, key.value(), asset.version());
        var object = assets.object(id, area);
        if (!object.key().equals(key.value()) || !object.version().equals(asset.version())) {
            throw new IllegalStateException("image object intent conflict");
        }
        if (object.state().equals("PERMANENT_FAILURE")) throw new IllegalStateException("image object requires investigation");
        long attempt = object.state().equals("VERIFIED") ? 0 : assets.begin(object);
        return new Work(object.id(), attempt, key, asset.transformed());
    }

    private void execute(Work work, Transfer transfer) throws IOException, ImageFailure {
        try {
            transfer.run();
        } catch (IOException | ImageFailure failure) {
            if (work.attempt() != 0) {
                boolean retryable = failure instanceof IOException
                        && !(failure instanceof ImageStorage.IntegrityMismatch || failure instanceof ImageStorage.ReadLimitExceeded);
                try {
                    transaction.executeWithoutResult(status -> assets.failed(work.id(), work.attempt(), retryable,
                            retryable ? "storage_unavailable" : "integrity_or_payload_invalid"));
                } catch (RuntimeException updateFailure) {
                    failure.addSuppressed(updateFailure);
                }
            }
            throw failure;
        }
        if (work.attempt() != 0 && !Boolean.TRUE.equals(transaction.execute(
                status -> assets.verified(work.id(), work.attempt(), work.media())))) {
            throw new IOException("image object attempt superseded");
        }
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

    private record Work(long id, long attempt, ImageStorage.ObjectKey key, ImageTransform.Info media) {}

    @FunctionalInterface
    private interface Transfer {
        void run() throws IOException, ImageFailure;
    }
}
