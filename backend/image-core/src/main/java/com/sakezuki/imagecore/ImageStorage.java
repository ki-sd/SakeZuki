package com.sakezuki.imagecore;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

public interface ImageStorage {
    String CACHE_CONTROL = "private, max-age=300, immutable";

    record ObjectKey(String value) {
        public ObjectKey {
            if (value == null || !value.matches("(?:sake/[1-9][0-9]{0,18}|asset/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})/image-v[1-9][0-9]{0,8}/[a-f0-9]{64}\\.(jpg|png)")) {
                throw new IllegalArgumentException("invalid image object key");
            }
            if (value.startsWith("sake/")) Long.parseLong(value.split("/")[1]);
        }

        public static ObjectKey forImage(long no, ImageTransform.Info image) {
            return new ObjectKey("sake/" + no + "/" + ImageTransform.VERSION + "/" + filename(image.mimeType(), image.sha256()));
        }

        public static ObjectKey forAsset(UUID id, String version, ImageTransform.Info image) {
            return new ObjectKey("asset/" + id + "/" + version + "/" + filename(image.mimeType(), image.sha256()));
        }

        boolean matches(String mime, String hash) {
            return value.endsWith("/" + filename(mime, hash));
        }

        private static String filename(String mime, String hash) {
            if (ImageTransform.PNG.equals(mime)) return hash + ".png";
            if (ImageTransform.JPEG.equals(mime)) return hash + ".jpg";
            throw new IllegalArgumentException("unsupported image object MIME");
        }

        boolean matches(ImageTransform.Info image) {
            return matches(image.mimeType(), image.sha256());
        }
    }

    record ObjectInfo(String contentType, String cacheControl, String declaredSha256, String actualSha256, int byteLength) {}

    record Payload(String contentType, String cacheControl, String declaredSha256, byte[] bytes) {}

    interface Readable extends ImageStorage {
        // Return an owned buffer and enforce the byte limit before returning.
        Optional<Payload> readPayload(ObjectKey key, int maxBytes) throws IOException;
    }

    final class ReadLimitExceeded extends IOException {
        public ReadLimitExceeded() { super("stored object exceeds verification limit"); }
    }

    final class ObjectMissing extends IOException {
        public ObjectMissing() { super("stored object missing"); }
    }

    final class IntegrityMismatch extends IOException {
        public IntegrityMismatch() { super("stored object integrity mismatch"); }
    }

    // Return false if the key exists. Creation must be atomic and never replace it.
    boolean createIfAbsent(ObjectKey key, String contentType, String sha256, byte[] bytes) throws IOException;

    // Read no more than maxBytes + 1, fail if the remote body exceeds maxBytes.
    Optional<ObjectInfo> read(ObjectKey key, int maxBytes) throws IOException;
}
