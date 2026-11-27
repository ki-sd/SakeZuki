package com.sakezuki.imagecore;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class R2ImageStorageTest {
    @Test
    void sendsConditionalPutAndTreatsPreconditionFailureAsAlreadyExists() throws IOException {
        byte[] bytes = ImageFixtures.png(5, 5);
        String sha = ImageTransform.sha256(bytes);
        var key = new ImageStorage.ObjectKey("sake/1/image-v1/" + sha + ".png");
        var requests = new AtomicInteger();
        var storage = new R2ImageStorage(s3((method, args) -> {
            assertEquals("putObject", method);
            var request = (PutObjectRequest) args[0];
            assertEquals("fixture", request.bucket());
            assertEquals(key.value(), request.key());
            assertEquals("*", request.ifNoneMatch());
            assertEquals("image/png", request.contentType());
            assertEquals(ImageStorage.CACHE_CONTROL, request.cacheControl());
            assertEquals(sha, request.metadata().get("sha256"));
            assertEquals(bytes.length, ((RequestBody) args[1]).optionalContentLength().orElseThrow());
            if (requests.incrementAndGet() == 2) {
                throw S3Exception.builder().statusCode(412).build();
            }
            return PutObjectResponse.builder().eTag("not-a-sha256").build();
        }), "fixture");
        assertTrue(storage.createIfAbsent(key, "image/png", sha, bytes));
        assertFalse(storage.createIfAbsent(key, "image/png", sha, bytes));
        assertThrows(IOException.class, () -> storage.createIfAbsent(key, "image/jpeg", sha, bytes));
        assertEquals(2, requests.get());
    }

    @Test
    void hashesDownloadedBytesSeparatelyFromMetadataAndBoundsReadBack() throws IOException {
        byte[] bytes = ImageFixtures.png(5, 5);
        String sha = ImageTransform.sha256(bytes);
        var key = new ImageStorage.ObjectKey("sake/1/image-v1/" + sha + ".png");
        var storage = new R2ImageStorage(s3((method, args) -> {
            assertEquals("getObject", method);
            assertEquals(key.value(), ((GetObjectRequest) args[0]).key());
            var response = GetObjectResponse.builder().contentType("image/png").cacheControl(ImageStorage.CACHE_CONTROL)
                    .metadata(Map.of("sha256", "wrong-digest")).eTag("not-a-sha256").build();
            return new ResponseInputStream<>(response, AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        }), "fixture");
        var read = storage.read(key, bytes.length).orElseThrow();
        assertEquals(sha, read.actualSha256());
        assertEquals("wrong-digest", read.declaredSha256());
        assertEquals(bytes.length, read.byteLength());
        assertThrows(IOException.class, () -> storage.read(key, bytes.length - 1));
    }

    @Test
    void missingObjectAndStorageFailureRemainDistinct() throws IOException {
        var key = new ImageStorage.ObjectKey("sake/1/image-v1/" + "0".repeat(64) + ".png");
        var storage = new R2ImageStorage(s3((method, args) -> {
            throw S3Exception.builder().statusCode(404).build();
        }), "fixture");
        assertTrue(storage.read(key, 100).isEmpty());
        var failing = new R2ImageStorage(s3((method, args) -> {
            throw S3Exception.builder().statusCode(503).build();
        }), "fixture");
        assertThrows(IOException.class, () -> failing.read(key, 100));
    }

    private static S3Client s3(BiFunction<String, Object[], Object> invocation) {
        return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] { S3Client.class },
                (instance, method, args) -> invocation.apply(method.getName(), args));
    }
}
