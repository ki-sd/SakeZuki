package com.sakezuki.imagecore;

import java.io.IOException;

public final class VerifiedImageStorage {
    private VerifiedImageStorage() {}

    public static boolean store(ImageStorage storage, ImageStorage.ObjectKey key, ImageTransform.Output converted)
            throws IOException, ImageFailure {
        var media = converted.transformed();
        int length = media.byteLength();
        var stored = storage.read(key, length);
        boolean created = false;
        if (stored.isEmpty()) {
            created = storage.createIfAbsent(key, media.mimeType(), media.sha256(), converted.bytes());
            stored = storage.read(key, length);
        }
        if (stored.isEmpty() || !verified(stored.get(), media)) {
            throw new ImageFailure(ImageFailure.Category.STORAGE_FAILED, "stored_object_integrity_mismatch");
        }
        return created;
    }

    private static boolean verified(ImageStorage.ObjectInfo stored, ImageTransform.Info expected) {
        return stored.byteLength() == expected.byteLength() && expected.mimeType().equals(stored.contentType())
                && ImageStorage.CACHE_CONTROL.equals(stored.cacheControl())
                && expected.sha256().equals(stored.declaredSha256()) && expected.sha256().equals(stored.actualSha256());
    }
}
