package com.sakezuki.backend.image;

import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.R2ImageStorage;
import jakarta.annotation.PreDestroy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Optional;

@Component
final class R2ImageStores implements ImageStores {
    private final EnumMap<Area, R2ImageStorage> clients = new EnumMap<>(Area.class);

    R2ImageStores(Environment env) {
        String staging = env.getProperty("IMAGE_STAGING_R2_BUCKET");
        String delivery = env.getProperty("IMAGE_DELIVERY_R2_BUCKET");
        String stagingKey = env.getProperty("IMAGE_STAGING_R2_ACCESS_KEY_ID");
        String deliveryKey = env.getProperty("IMAGE_DELIVERY_R2_ACCESS_KEY_ID");
        try {
            if (staging != null && !staging.isBlank() && delivery != null && !delivery.isBlank()
                    && (staging.equals(delivery) || (stagingKey != null && stagingKey.equals(deliveryKey)))) {
                throw new IllegalArgumentException("image storage areas must use distinct buckets and credentials");
            }
            for (Area area : Area.values()) {
                String prefix = "IMAGE_" + area.name() + "_R2_";
                String bucket = env.getProperty(prefix + "BUCKET");
                if (bucket == null || bucket.isBlank()) continue;
                clients.put(area, R2ImageStorage.connect(required(env, prefix + "ENDPOINT"), bucket,
                        env.getProperty(prefix + "REGION", "auto"), required(env, prefix + "ACCESS_KEY_ID"),
                        required(env, prefix + "SECRET_ACCESS_KEY"), null));
            }
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    @Override
    public Optional<ImageStorage.Readable> storage(Area area) {
        return Optional.ofNullable(clients.get(area));
    }

    @PreDestroy
    void close() {
        clients.values().forEach(R2ImageStorage::close);
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }
}
