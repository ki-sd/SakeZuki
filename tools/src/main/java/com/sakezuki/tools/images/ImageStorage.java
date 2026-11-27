package com.sakezuki.tools.images;

import java.io.IOException;
import java.util.Optional;

public interface ImageStorage {
    String CACHE_CONTROL = "private, max-age=300, immutable";

    record ObjectKey(String value) {
        public ObjectKey {
            if (value == null || !value.matches("sake/[1-9][0-9]{0,18}/image-v[1-9][0-9]{0,8}/[a-f0-9]{64}\\.(jpg|png)")) {
                throw new IllegalArgumentException("invalid image object key");
            }
            Long.parseLong(value.split("/")[1]);
        }

        static ObjectKey forImage(long no, ImageTransform.Info image) {
            String suffix = ImageTransform.PNG.equals(image.mimeType()) ? ".png" : ".jpg";
            return new ObjectKey("sake/" + no + "/" + ImageTransform.VERSION + "/" + image.sha256() + suffix);
        }
    }

    record ObjectInfo(String contentType, String cacheControl, String declaredSha256, String actualSha256, int byteLength) {}

    // Return false if the key exists. Creation must be atomic and never replace it.
    boolean createIfAbsent(ObjectKey key, String contentType, String sha256, byte[] bytes) throws IOException;

    // Read no more than maxBytes + 1, fail if the remote body exceeds maxBytes.
    Optional<ObjectInfo> read(ObjectKey key, int maxBytes) throws IOException;
}
