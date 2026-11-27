package com.sakezuki.tools.images;

import com.sakezuki.imagecore.ImageFailure;
import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import com.sakezuki.imagecore.VerifiedImageStorage;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

public final class ImagePipeline {
    public enum Status {
        APPROVAL_PENDING, RIGHTS_UNCONFIRMED, RIGHTS_DENIED, IMAGE_ABSENT, INVALID_SOURCE,
        DOWNLOAD_FAILED, INVALID_IMAGE, TRANSFORM_FAILED, STORAGE_FAILED, STORED_VERIFIED
    }

    public record Media(String mimeType, int byteLength, int width, int height, String sha256) {
        private static Media from(ImageTransform.Info info) {
            return new Media(info.mimeType(), info.byteLength(), info.width(), info.height(), info.sha256());
        }
    }

    public record Asset(URI finalImageUrl, String transformVersion, Media original, Media transformed,
                        ImageStorage.ObjectKey objectKey, boolean newlyCreated) {}
    public record Result(Status status, String reason, Duration retryAfter, Asset asset) {}

    static final class Failure extends Exception {
        final Status status;
        final Duration retryAfter;

        Failure(Status status, String reason) {
            this(status, reason, null);
        }

        Failure(Status status, String reason, Duration retryAfter) {
            super(reason);
            this.status = status;
            this.retryAfter = retryAfter;
        }
    }

    private final ImageApproval.Authority authority;
    private final SecureImageDownload download;
    private final ImageStorage storage;
    private final Clock clock;
    private final Set<String> placeholderHashes;

    public ImagePipeline(ImageApproval.Authority authority, SecureImageDownload download, ImageStorage storage,
                         Clock clock, Set<String> placeholderHashes) {
        this.authority = authority;
        this.download = download;
        this.storage = storage;
        this.clock = clock;
        this.placeholderHashes = Set.copyOf(placeholderHashes);
    }

    public Result process(ImageApproval.Request request) {
        try {
            var grant = ImageApproval.require(request, authority, clock.instant());
            var source = download.download(request.sourceImageUrl(), Set.copyOf(grant.permittedUrls()));
            var converted = ImageTransform.convert(source.contentType(), source.bytes(), placeholderHashes);
            // Re-check the trusted grant immediately before creating or confirming an object.
            grant = ImageApproval.require(request, authority, clock.instant());
            if (!grant.permittedUrls().contains(source.finalUrl())) {
                throw new Failure(Status.RIGHTS_UNCONFIRMED, "final_url_outside_current_grant");
            }
            var media = converted.transformed();
            var key = ImageStorage.ObjectKey.forImage(request.mapping().no(), media);
            boolean created = VerifiedImageStorage.store(storage, key, converted);
            return new Result(Status.STORED_VERIFIED, "stored_object_verified", null,
                    new Asset(source.finalUrl(), ImageTransform.VERSION, Media.from(converted.original()),
                            Media.from(media), key, created));
        } catch (Failure ex) {
            return new Result(ex.status, ex.getMessage(), ex.retryAfter, null);
        } catch (ImageFailure ex) {
            Status status = switch (ex.category()) {
                case IMAGE_ABSENT -> Status.IMAGE_ABSENT;
                case INVALID_IMAGE -> Status.INVALID_IMAGE;
                case TRANSFORM_FAILED -> Status.TRANSFORM_FAILED;
                case STORAGE_FAILED -> Status.STORAGE_FAILED;
            };
            return new Result(status, ex.getMessage(), null, null);
        } catch (IOException ex) {
            return new Result(Status.STORAGE_FAILED, "storage_io_failure", null, null);
        }
    }

}
