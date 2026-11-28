package com.sakezuki.backend.ugc;

import com.sakezuki.backend.image.ImageAssetService;
import com.sakezuki.backend.image.ImageStores;
import com.sakezuki.imagecore.ImageFailure;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import com.sakezuki.imagecore.VerifiedImageStorage;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.Semaphore;

@Service
class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository uploads;
    private final SubmissionRepository submissions;
    private final SubmissionLimits limits;
    private final ImageAssetService images;
    private final ImageStores stores;
    private final TransactionTemplate transaction;
    private final Semaphore previews = new Semaphore(2);

    AttachmentService(AttachmentRepository uploads, SubmissionRepository submissions, SubmissionLimits limits,
                      ImageAssetService images, ImageStores stores, PlatformTransactionManager manager) {
        this.uploads = uploads;
        this.submissions = submissions;
        this.limits = limits;
        this.images = images;
        this.stores = stores;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    record Upload(String attachmentId, String status, LocalDateTime expiresAt, String previewUrl) {}

    Upload upload(long owner, UUID key, String mime, byte[] bytes) {
        if (bytes.length > SubmissionLimits.FILE_BYTES) throw SubmissionErrors.error(413, "이미지 용량을 줄여 주세요.");
        if (!(ImageTransform.JPEG.equals(mime) || ImageTransform.PNG.equals(mime))) {
            throw SubmissionErrors.error(415, "JPEG 또는 PNG만 업로드할 수 있습니다.");
        }
        if (bytes.length == 0) throw SubmissionInput.invalid("file");
        String hash = mime + ":" + ImageTransform.sha256(bytes);
        var ready = uploads.ready(owner,key);
        if (ready.isPresent()) {
            if (!hash.equals(ready.get().hash())) throw SubmissionErrors.conflict();
            return receipt(ready.get());
        }
        try (var permit = images.admit()) {
            return uploadAdmitted(owner,key,mime,bytes,hash,permit);
        } catch (IOException busy) {
            throw unavailable();
        }
    }

    private Upload uploadAdmitted(long owner, UUID key, String mime, byte[] bytes, String hash,
                                  ImageAssetService.Processing permit) {
        var intent = transaction.execute(status -> claim(owner, key, hash));
        if ("READY".equals(intent.status())) return receipt(intent);

        try {
            UUID id = UUID.fromString(intent.id());
            permit.stage(id, ImageAssetService.Source.USER_SUBMISSION, "submission-upload:" + id, mime, bytes);
            boolean finished = Boolean.TRUE.equals(transaction.execute(status -> {
                submissions.lockActiveMember(owner);
                return uploads.finish(owner, intent);
            }));
            if (!finished) throw SubmissionErrors.conflict();
            return receipt(intent);
        } catch (ImageFailure failure) {
            boolean invalid = failure.category() == ImageFailure.Category.INVALID_IMAGE
                    || failure.category() == ImageFailure.Category.IMAGE_ABSENT;
            saveFailure(owner, intent, invalid ? "INVALID" : "RETRYABLE_FAILURE", failure);
            if (invalid) throw SubmissionInput.invalid("file");
            throw unavailable();
        } catch (IOException | DataAccessException | org.springframework.transaction.TransactionException
                 | ImageAssetService.QuarantinedImageException failure) {
            saveFailure(owner, intent, "RETRYABLE_FAILURE", failure);
            throw unavailable();
        } catch (org.springframework.web.server.ResponseStatusException failure) {
            saveFailure(owner, intent, "RETRYABLE_FAILURE", failure);
            throw failure;
        }
    }

    private AttachmentRepository.Intent claim(long owner, UUID key, String hash) {
        submissions.lockActiveMember(owner);
        var existing = uploads.existing(owner, key);
        if (existing.isPresent()) {
            var prior = existing.get();
            if (!prior.hash().equals(hash)) throw SubmissionErrors.conflict();
            if ("READY".equals(prior.status())) return prior;
            if (prior.expired()) throw SubmissionErrors.missing();
            if ("INVALID".equals(prior.status())) throw SubmissionInput.invalid("file");
            if (prior.leased()) throw SubmissionErrors.conflict();
            uploads.reclaim(owner, prior);
        } else {
            if (uploads.recent(owner) >= limits.uploadsPerDay()
                    || uploads.reservedBytes(owner) > limits.reservedBytes() - ImageTransform.MAX_BYTES) {
                throw SubmissionService.quota();
            }
            if (uploads.hasLiveCopy(owner, hash)) {
                throw SubmissionErrors.error(409, "이미 등록 중이거나 보관 중인 첨부입니다.");
            }
            uploads.insert(owner, UUID.randomUUID(), key, hash, ImageTransform.MAX_BYTES, limits.unlinkedHours());
        }
        return uploads.existing(owner, key).orElseThrow();
    }

    private void saveFailure(long owner, AttachmentRepository.Intent intent, String next, Exception failure) {
        try {
            transaction.executeWithoutResult(status -> uploads.fail(owner, intent, next));
        } catch (RuntimeException updateFailure) {
            log.warn("UGC upload recovery state write failed: operation={}, update={}",
                    failure.getClass().getSimpleName(), updateFailure.getClass().getSimpleName());
        }
    }

    void preview(long owner, UUID id, UUID submission, HttpServletResponse response) throws IOException {
        preview(() -> uploads.preview(owner, id, submission), response, false);
    }

    void preview(Supplier<Optional<AttachmentRepository.Preview>> authorizedLookup, HttpServletResponse response,
                 boolean requireSameGeneration) throws IOException {
        var candidate = authorizedLookup.get().orElseThrow(SubmissionErrors::missing);
        if (!previews.tryAcquire()) throw unavailable();
        try {
            var storage = stores.storage(ImageStores.Area.STAGING).orElseThrow(AttachmentService::unavailable);
            byte[] bytes;
            try {
                bytes = VerifiedImageStorage.readVerified(storage, new ImageStorage.ObjectKey(candidate.key()), candidate.media());
            } catch (IOException failure) {
                images.reportVerifiedReadFailure(candidate.objectId(), candidate.generation(), failure);
                throw unavailable();
            }
            var current = authorizedLookup.get();
            if (current.isEmpty() || current.get().objectId() != candidate.objectId()
                    || (requireSameGeneration && current.get().generation() != candidate.generation())
                    || !current.get().media().equals(candidate.media())) {
                throw SubmissionErrors.missing();
            }
            response.setContentType(candidate.media().mimeType());
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } finally {
            previews.release();
        }
    }

    private static Upload receipt(AttachmentRepository.Intent intent) {
        return new Upload(intent.id(), "READY", intent.expiresAt(),
                UgcController.ROOT + "/uploads/" + intent.id() + "/preview");
    }

    private static org.springframework.web.server.ResponseStatusException unavailable() {
        return SubmissionErrors.error(503, "이미지 저장소를 지금 사용할 수 없습니다. 잠시 뒤 다시 시도해 주세요.");
    }
}
