package com.sakezuki.imagecore;

import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
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
        byte[] html = "<html>not an image</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String htmlSha = ImageTransform.sha256(html);
        var forgedKey = new ImageStorage.ObjectKey("sake/1/image-v1/" + htmlSha + ".png");
        assertThrows(IOException.class, () -> storage.createIfAbsent(forgedKey, "image/png", htmlSha, html));
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

    @Test
    void explicitUrlConnectionClientPerformsSignedConditionalPutAndGetAgainstLoopbackS3() throws Exception {
        byte[] bytes = ImageFixtures.png(5, 5);
        String sha = ImageTransform.sha256(bytes);
        var key = new ImageStorage.ObjectKey("sake/1/image-v1/" + sha + ".png");
        var created = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/fixture/" + key.value(), exchange -> {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                String signature = headers.getFirst("Authorization");
                if (signature == null || !signature.startsWith("AWS4-HMAC-SHA256 ")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                if ("PUT".equals(exchange.getRequestMethod())) {
                    byte[] sent = exchange.getRequestBody().readAllBytes();
                    if (!"*".equals(headers.getFirst("If-None-Match"))
                            || !sha.equals(headers.getFirst("x-amz-meta-sha256"))
                            || !matchesFixtureBody(bytes, sent, headers.getFirst("Content-Encoding"))) {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    exchange.sendResponseHeaders(created.compareAndSet(false, true) ? 200 : 412, -1);
                } else if (created.get() && "GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.getResponseHeaders().set("Cache-Control", ImageStorage.CACHE_CONTROL);
                    exchange.getResponseHeaders().set("x-amz-meta-sha256", sha);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var direct = UrlConnectionHttpClient.builder().proxyConfiguration(ProxyConfiguration.builder()
                .useSystemPropertyValues(false).useEnvironmentVariablesValues(false).build());
        try (var storage = R2ImageStorage.connectEndpoint(endpoint, "fixture", "auto", "test-key", "test-secret", null, direct)) {
            assertTrue(storage.read(key, bytes.length).isEmpty());
            assertTrue(storage.createIfAbsent(key, "image/png", sha, bytes));
            assertFalse(storage.createIfAbsent(key, "image/png", sha, bytes));
            assertArrayEquals(bytes, VerifiedImageStorage.readVerified(storage, key,
                    new ImageTransform.Info("image/png", bytes.length, 5, 5, sha)));
        } finally {
            server.stop(0);
        }
    }

    private static boolean matchesFixtureBody(byte[] expected, byte[] sent, String encoding) {
        if (encoding == null) return java.util.Arrays.equals(expected, sent);
        if (!"aws-chunked".equals(encoding)) return false;
        // The small fixture is sent as one signed data chunk plus the terminating chunk.
        String wire = new String(sent, java.nio.charset.StandardCharsets.ISO_8859_1);
        int lineEnd = wire.indexOf("\r\n");
        int signature = wire.indexOf(";chunk-signature=");
        if (signature <= 0 || signature >= lineEnd || lineEnd + 2 + expected.length >= sent.length) return false;
        return Integer.parseInt(wire.substring(0, signature), 16) == expected.length
                && java.util.Arrays.equals(expected, java.util.Arrays.copyOfRange(sent, lineEnd + 2, lineEnd + 2 + expected.length));
    }

    private static S3Client s3(BiFunction<String, Object[], Object> invocation) {
        return (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[] { S3Client.class },
                (instance, method, args) -> invocation.apply(method.getName(), args));
    }
}
