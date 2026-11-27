package com.sakezuki.tools.images;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class R2DevSmokeTest {
    @Test
    void rejectsMissingConsentEvenForDevelopmentBucket() {
        assertThrows(R2DevSmoke.CheckFailure.class,
                () -> R2DevSmoke.requireTarget(Map.of("R2_BUCKET", "sakezuki-images-dev")));
    }

    @Test
    void rejectsOtherBucketsEvenWithConsent() {
        assertThrows(R2DevSmoke.CheckFailure.class, () -> R2DevSmoke.requireTarget(
                Map.of("R2_BUCKET", "another-bucket", "R2_SMOKE_CONFIRM", "sakezuki-images-dev")));
    }
}
