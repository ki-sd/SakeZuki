package com.sakezuki.imagecore;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public final class R2ImageStorage implements ImageStorage, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    public static R2ImageStorage fromEnvironment() {
        return connect(required("R2_ENDPOINT"), required("R2_BUCKET"), System.getenv().getOrDefault("R2_REGION", "auto"),
                required("R2_ACCESS_KEY_ID"), required("R2_SECRET_ACCESS_KEY"), null);
    }

    public static R2ImageStorage connect(String endpoint, String bucket, String region, String accessKey, String secretKey,
                                  ExecutionInterceptor observer) {
        if (!endpoint.matches("https://[a-f0-9]{32}\\.r2\\.cloudflarestorage\\.com/?")) {
            throw new IllegalArgumentException("R2_ENDPOINT must be an account HTTPS S3 endpoint");
        }
        requireBucket(bucket);
        if (!"auto".equals(region)) {
            throw new IllegalArgumentException("R2_REGION must be auto");
        }
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint)).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKey, secretKey)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(c -> {
                    c.apiCallTimeout(Duration.ofSeconds(40)).apiCallAttemptTimeout(Duration.ofSeconds(15));
                    if (observer != null) {
                        c.addExecutionInterceptor(observer);
                    }
                })
                .build();
        return new R2ImageStorage(client, bucket);
    }

    R2ImageStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = requireBucket(bucket);
    }

    @Override
    public boolean createIfAbsent(ObjectKey key, String contentType, String sha256, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > ImageTransform.MAX_BYTES
                || !ImageTransform.sha256(bytes).equals(sha256)
                || !(ImageTransform.PNG.equals(contentType) && key.value().endsWith("/" + sha256 + ".png")
                || ImageTransform.JPEG.equals(contentType) && key.value().endsWith("/" + sha256 + ".jpg"))) {
            throw new IOException("invalid object payload");
        }
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key.value())
                    .ifNoneMatch("*").contentType(contentType).cacheControl(CACHE_CONTROL)
                    .metadata(Map.of("sha256", sha256)).build(), RequestBody.fromBytes(bytes));
            return true;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 412) {
                return false;
            }
            throw new IOException("object upload failed", ex);
        } catch (SdkException ex) {
            throw new IOException("object upload failed", ex);
        }
    }

    @Override
    public Optional<ObjectInfo> read(ObjectKey key, int maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > ImageTransform.MAX_BYTES) {
            throw new IOException("invalid object read limit");
        }
        try (var input = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key.value()).build())) {
            var response = input.response();
            if (response.contentLength() != null && response.contentLength() > maxBytes) {
                input.abort();
                throw new IOException("stored object exceeds verification limit");
            }
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                input.abort();
                throw new IOException("stored object exceeds verification limit");
            }
            return Optional.of(new ObjectInfo(response.contentType(), response.cacheControl(),
                    response.metadata().get("sha256"), ImageTransform.sha256(bytes), bytes.length));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404 && !"NoSuchBucket".equals(
                    ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode())) {
                return Optional.empty();
            }
            throw new IOException("object verification read failed", ex);
        } catch (SdkException ex) {
            throw new IOException("object verification read failed", ex);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static String requireBucket(String bucket) {
        if (!bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("invalid R2_BUCKET");
        }
        return bucket;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
