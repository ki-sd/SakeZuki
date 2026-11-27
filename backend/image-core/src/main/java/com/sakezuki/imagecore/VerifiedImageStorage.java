package com.sakezuki.imagecore;

import java.io.IOException;

public final class VerifiedImageStorage {
    private VerifiedImageStorage() {}

    public static boolean store(ImageStorage storage, ImageStorage.ObjectKey key, ImageTransform.Output converted)
            throws IOException, ImageFailure {
        var media = converted.transformed();
        int length = media.byteLength();
        byte[] bytes = converted.bytes();
        if (!key.matches(media) || bytes.length != length || !media.sha256().equals(ImageTransform.sha256(bytes))) {
            throw new ImageFailure(ImageFailure.Category.INVALID_IMAGE, "invalid_transformed_payload");
        }
        ImageTransform.validateTransformed(media, bytes);
        try {
            return store(storage, key, media, bytes);
        } catch (ImageStorage.ReadLimitExceeded ex) {
            throw new ImageFailure(ImageFailure.Category.STORAGE_FAILED, "stored_object_integrity_mismatch");
        }
    }

    public static boolean copyVerified(ImageStorage.Readable source, ImageStorage destination,
                                       ImageStorage.ObjectKey key, ImageTransform.Info expected)
            throws IOException, ImageFailure {
        byte[] bytes = readVerified(source, key, expected);
        ImageTransform.validateTransformed(expected, bytes);
        try {
            return store(destination, key, expected, bytes);
        } catch (ImageStorage.ReadLimitExceeded ex) {
            throw new ImageFailure(ImageFailure.Category.STORAGE_FAILED, "stored_object_integrity_mismatch");
        }
    }

    private static boolean store(ImageStorage storage, ImageStorage.ObjectKey key, ImageTransform.Info media, byte[] bytes)
            throws IOException, ImageFailure {
        int length = media.byteLength();
        var stored = storage.read(key, length);
        boolean created = false;
        if (stored.isEmpty()) {
            created = storage.createIfAbsent(key, media.mimeType(), media.sha256(), bytes);
            stored = storage.read(key, length);
        }
        if (stored.isEmpty()) throw new IOException("stored object unavailable after write");
        if (!verified(stored.get(), media)) {
            throw new ImageFailure(ImageFailure.Category.STORAGE_FAILED, "stored_object_integrity_mismatch");
        }
        return created;
    }

    public static byte[] readVerified(ImageStorage.Readable storage, ImageStorage.ObjectKey key,
                                      ImageTransform.Info expected) throws IOException {
        if (!key.matches(expected)) throw new ImageStorage.IntegrityMismatch();
        var payload = storage.readPayload(key, expected.byteLength()).orElseThrow(() -> new IOException("image missing"));
        byte[] bytes = payload.bytes();
        var info = new ImageStorage.ObjectInfo(payload.contentType(), payload.cacheControl(), payload.declaredSha256(),
                ImageTransform.sha256(bytes), bytes.length);
        if (!verified(info, expected)) throw new ImageStorage.IntegrityMismatch();
        return bytes;
    }

    private static boolean verified(ImageStorage.ObjectInfo stored, ImageTransform.Info expected) {
        return stored.byteLength() == expected.byteLength() && expected.mimeType().equals(stored.contentType())
                && ImageStorage.CACHE_CONTROL.equals(stored.cacheControl())
                && expected.sha256().equals(stored.declaredSha256()) && expected.sha256().equals(stored.actualSha256());
    }
}
