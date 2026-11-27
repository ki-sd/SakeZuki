package com.sakezuki.tools.images;

import com.sakezuki.imagecore.ImageStorage;
import com.sakezuki.imagecore.ImageTransform;
import com.sakezuki.imagecore.R2ImageStorage;
import com.sakezuki.imagecore.VerifiedImageStorage;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.exception.SdkServiceException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class R2DevSmoke {
    private static final String BUCKET = "sakezuki-images-dev";

    private record Case(ImageStorage.ObjectKey key, ImageTransform.Output output, byte[] original) {}
    private record Credentials(String accessKey, String secretKey) {
        @Override public String toString() { return "[redacted]"; }
    }

    private R2DevSmoke() {}

    public static void main(String[] args) {
        var counts = new Traffic();
        int exit = 0;
        try {
            run(System.getenv(), counts);
        } catch (Exception ex) {
            String reason = ex instanceof CheckFailure check ? check.getMessage() : ex.getClass().getSimpleName();
            System.err.printf("R2 smoke failed: %s (HTTP status: %d)%n", reason, s3Status(ex));
            exit = 1;
        } finally {
            System.out.printf("SDK HTTP attempts: GET=%d PUT=%d; HTTP 412=%d%n",
                    counts.gets, counts.puts, counts.preconditionFailures);
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    private static void run(Map<String, String> env, Traffic counts) throws Exception {
        requireTarget(env);
        String endpoint = required(env, "R2_ENDPOINT");
        String region = required(env, "R2_REGION");
        var writer = credentials(env, "R2_");
        boolean hasReadOnly = env.containsKey("R2_READONLY_ACCESS_KEY_ID") || env.containsKey("R2_READONLY_SECRET_ACCESS_KEY");
        var reader = hasReadOnly ? credentials(env, "R2_READONLY_") : null;
        check(reader == null || !writer.accessKey().equals(reader.accessKey()), "read_only_key_must_be_distinct");

        var random = new SecureRandom();
        long marker = 8_000_000_000_000_000_000L + random.nextLong(1_000_000_000_000_000_000L);
        var cases = List.of(fixture(marker, "jpeg", random), fixture(marker, "png", random));
        var spare = fixture(marker, "png", random);
        check(cases.stream().noneMatch(item -> item.key().equals(spare.key())), "synthetic_key_collision");
        Path manifest = Path.of("build", "r2-smoke", marker + ".tsv");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "state\tkey\tbytes\n", StandardOpenOption.CREATE_NEW);
        System.out.printf("bucket=%s run=%d manifest=%s%n", BUCKET, marker, manifest);
        for (Case item : cases) {
            record(manifest, "PLANNED", item);
        }
        if (reader != null) {
            record(manifest, "PERMISSION_PROBE", spare);
        }
        try (var storage = connect(endpoint, region, writer, counts)) {
            check(storage.read(spare.key(), spare.output().transformed().byteLength()).isEmpty(), "expected_missing_object");
            System.out.println("Authenticated GET of missing key: 404 mapped to empty");
            for (Case item : cases) {
                try {
                    exercise(storage, counts, item, manifest);
                } catch (Exception ex) {
                    System.err.println("Review manifest before cleanup; a planned PUT may have succeeded.");
                    throw ex;
                }
            }
            if (reader == null) {
                System.out.println("Read-only permission probe: SKIPPED (no separate key supplied)");
            } else {
                try (var readOnly = connect(endpoint, region, reader, counts)) {
                    var first = cases.getFirst();
                    check(readOnly.read(first.key(), first.output().transformed().byteLength()).isPresent(),
                            "read_only_get_failed");
                    try {
                        if (put(readOnly, spare)) {
                            record(manifest, "UNEXPECTED_CREATED", spare);
                            counts.createdBytes += spare.output().transformed().byteLength();
                        }
                        throw new CheckFailure("read_only_put_was_not_denied");
                    } catch (IOException ex) {
                        if (s3Status(ex) != 403) {
                            throw ex;
                        }
                        System.out.println("Read-only GET succeeded; PUT denied with HTTP 403");
                    }
                }
            }
        } finally {
            System.out.printf("Bytes acknowledged created: %d; cleanup manifest: %s%n", counts.createdBytes, manifest);
        }
        System.out.println(reader == null ? "R2 core checks passed; read-only probe skipped" : "R2 development-bucket smoke passed");
    }

    private static void exercise(R2ImageStorage storage, Traffic counts, Case item, Path manifest) throws Exception {
        var output = item.output();
        check(storage.read(item.key(), output.transformed().byteLength()).isEmpty(), "test_key_already_exists");
        check(put(storage, item), "conditional_create_did_not_create");
        counts.createdBytes += output.transformed().byteLength();
        record(manifest, "CREATED", item);
        int putCount = counts.puts;
        check(!VerifiedImageStorage.store(storage, item.key(), output), "duplicate_path_attempted_create");
        check(counts.puts == putCount, "duplicate_path_sent_put");

        var repeated = ImageTransform.convert(output.original().mimeType(), item.original(), Set.of());
        check(repeated.transformed().equals(output.transformed()), "repeat_transform_mismatch");
        check(!VerifiedImageStorage.store(storage, item.key(), repeated) && counts.puts == putCount,
                "repeat_processing_not_deduplicated");
        int before412 = counts.preconditionFailures;
        check(!put(storage, item) && counts.preconditionFailures == before412 + 1, "expected_http_412");
        check(!VerifiedImageStorage.store(storage, item.key(), output), "post_412_object_not_reused");
        int beforeBadPayload = counts.puts;
        byte[] corrupted = output.bytes().clone();
        corrupted[0] ^= 1;
        try {
            storage.createIfAbsent(item.key(), output.transformed().mimeType(), output.transformed().sha256(), corrupted);
            throw new CheckFailure("corrupted_payload_accepted");
        } catch (IOException ex) {
            check("invalid object payload".equals(ex.getMessage()) && counts.puts == beforeBadPayload,
                    "corrupted_payload_not_rejected_locally");
        }
        System.out.printf("key=%s MIME=%s bytes=%d originalSHA256=%s storedSHA256=%s PUT/GET/reuse/412=OK%n",
                item.key().value(), output.transformed().mimeType(), output.transformed().byteLength(),
                output.original().sha256(), output.transformed().sha256());
    }

    private static boolean put(R2ImageStorage storage, Case item) throws IOException {
        var media = item.output().transformed();
        return storage.createIfAbsent(item.key(), media.mimeType(), media.sha256(), item.output().bytes());
    }

    private static Case fixture(long marker, String format, SecureRandom random) throws Exception {
        boolean png = format.equals("png");
        var image = new BufferedImage(180, 90, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, png ? random.nextInt() : random.nextInt() | 0xff000000);
            }
        }
        var bytes = new ByteArrayOutputStream();
        check(ImageIO.write(image, format, bytes), "synthetic_image_writer_unavailable");
        byte[] source = bytes.toByteArray();
        var output = ImageTransform.convert(png ? ImageTransform.PNG : ImageTransform.JPEG, source, Set.of());
        return new Case(ImageStorage.ObjectKey.forImage(marker, output.transformed()), output, source);
    }

    private static R2ImageStorage connect(String endpoint, String region, Credentials keys, Traffic counts) {
        return R2ImageStorage.connect(endpoint, BUCKET, region, keys.accessKey(), keys.secretKey(), counts);
    }

    static void requireTarget(Map<String, String> env) throws CheckFailure {
        check(BUCKET.equals(env.get("R2_BUCKET")) && BUCKET.equals(env.get("R2_SMOKE_CONFIRM")),
                "explicit_development_bucket_confirmation_required");
        check(Runtime.version().feature() == 21, "Java_21_required");
    }

    private static Credentials credentials(Map<String, String> env, String prefix) throws CheckFailure {
        return new Credentials(required(env, prefix + "ACCESS_KEY_ID"), required(env, prefix + "SECRET_ACCESS_KEY"));
    }

    private static String required(Map<String, String> env, String key) throws CheckFailure {
        String value = env.get(key);
        check(value != null && !value.isBlank(), "missing_" + key);
        return value;
    }

    private static void record(Path manifest, String state, Case item) throws IOException {
        Files.writeString(manifest, state + "\t" + item.key().value() + "\t"
                + item.output().transformed().byteLength() + "\n", StandardOpenOption.APPEND);
    }

    private static int s3Status(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SdkServiceException s3) {
                return s3.statusCode();
            }
        }
        return 0;
    }

    private static void check(boolean condition, String reason) throws CheckFailure {
        if (!condition) {
            throw new CheckFailure(reason);
        }
    }

    static final class CheckFailure extends Exception {
        CheckFailure(String reason) { super(reason); }
    }

    private static final class Traffic implements ExecutionInterceptor {
        int gets;
        int puts;
        int preconditionFailures;
        long createdBytes;

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            switch (context.httpRequest().method()) {
                case GET -> gets++;
                case PUT -> puts++;
                default -> throw new IllegalStateException("unexpected smoke HTTP method");
            }
        }

        @Override
        public void afterTransmission(Context.AfterTransmission context, ExecutionAttributes attributes) {
            if (context.httpResponse().statusCode() == 412) {
                preconditionFailures++;
            }
        }
    }
}
