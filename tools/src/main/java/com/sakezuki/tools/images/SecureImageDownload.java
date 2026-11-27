package com.sakezuki.tools.images;

import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.sakezuki.tools.images.ImagePipeline.Status.*;

public final class SecureImageDownload {
    static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(5);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    @FunctionalInterface
    interface Resolver { List<InetAddress> resolve(String host) throws IOException; }
    @FunctionalInterface
    interface Transport { Response get(URI uri, InetAddress address) throws IOException; }
    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    interface Response extends AutoCloseable {
        int status();
        String header(String name);
        InputStream body();
        @Override void close();
    }

    record Download(String contentType, byte[] bytes, URI finalUrl) {}
    private record Step(URI location, Download download, Duration retryDelay) {}

    private final Set<String> allowedHosts;
    private final Resolver resolver;
    private final Transport transport;
    private final Clock clock;
    private final Sleeper sleeper;

    public static SecureImageDownload direct(Set<String> allowedHosts, Clock clock) {
        return new SecureImageDownload(allowedHosts, host -> List.of(InetAddress.getAllByName(host)),
                SecureImageDownload::pinnedGet, clock, Thread::sleep);
    }

    SecureImageDownload(Set<String> allowedHosts, Resolver resolver, Transport transport, Clock clock, Sleeper sleeper) {
        this.allowedHosts = allowedHosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver;
        this.transport = transport;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Download download(URI original, Set<URI> permittedUrls) throws ImagePipeline.Failure {
        Duration delay = Duration.ZERO;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (!delay.isZero()) {
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ImagePipeline.Failure(DOWNLOAD_FAILED, "download_interrupted");
                }
            }
            try {
                Step result = follow(original, permittedUrls);
                if (result.download() != null) {
                    return result.download();
                }
                delay = result.retryDelay();
                if (delay.compareTo(MAX_RETRY_WAIT) > 0) {
                    throw new ImagePipeline.Failure(DOWNLOAD_FAILED, "retry_after_exceeds_budget", delay);
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new ImagePipeline.Failure(DOWNLOAD_FAILED, "retryable_http_status", delay);
                }
            } catch (IOException ex) {
                if (attempt == MAX_ATTEMPTS || Thread.currentThread().isInterrupted()) {
                    throw new ImagePipeline.Failure(DOWNLOAD_FAILED, "network_or_read_failure");
                }
                delay = Duration.ofMillis(200L * attempt);
            }
        }
        throw new IllegalStateException("download attempt budget exhausted");
    }

    private Step follow(URI original, Set<URI> permittedUrls) throws ImagePipeline.Failure, IOException {
        URI current = original;
        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            String host = requireHost(current);
            if (!permittedUrls.contains(current)) {
                throw new ImagePipeline.Failure(RIGHTS_UNCONFIRMED, "redirect_url_outside_reviewed_grant");
            }
            List<InetAddress> addresses = List.copyOf(resolver.resolve(host));
            if (addresses.isEmpty()) {
                throw new UnknownHostException(host);
            }
            if (addresses.stream().anyMatch(address -> !publicAddress(address))) {
                throw new ImagePipeline.Failure(INVALID_SOURCE, "non_public_dns_address");
            }
            try (Response response = transport.get(current, addresses.getFirst())) {
                Step result = inspect(current, response);
                if (result.location() == null) {
                    return result;
                }
                current = result.location();
            }
        }
        throw new ImagePipeline.Failure(INVALID_SOURCE, "redirect_limit");
    }

    private Step inspect(URI current, Response response) throws ImagePipeline.Failure, IOException {
        int status = response.status();
        if (REDIRECTS.contains(status)) {
            try {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new IllegalArgumentException();
                }
                return new Step(current.resolve(location), null, null);
            } catch (IllegalArgumentException ex) {
                throw new ImagePipeline.Failure(INVALID_SOURCE, "invalid_redirect_location");
            }
        }
        if (status == 404 || status == 410) {
            throw new ImagePipeline.Failure(IMAGE_ABSENT, "source_http_" + status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new Step(null, null, retryAfter(response.header("Retry-After")));
        }
        if (status != 200) {
            throw new ImagePipeline.Failure(DOWNLOAD_FAILED, "source_http_" + status);
        }
        String encoding = response.header("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "encoded_http_body");
        }
        String contentType = ImageTransform.requireMime(response.header("Content-Type"));
        long advertised = contentLength(response.header("Content-Length"));
        if (advertised > MAX_BYTES) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "download_byte_limit");
        }
        byte[] bytes = response.body().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "download_byte_limit");
        }
        if (advertised >= 0 && bytes.length != advertised) {
            throw new IOException("incomplete HTTP body");
        }
        return new Step(null, new Download(contentType, bytes, current), null);
    }

    private static long contentLength(String value) throws IOException {
        if (value == null) {
            return -1;
        }
        try {
            long length = Long.parseLong(value);
            if (length >= 0) {
                return length;
            }
        } catch (NumberFormatException ignored) {
            // Reject malformed framing.
        }
        throw new IOException("invalid content length");
    }

    private String requireHost(URI uri) throws ImagePipeline.Failure {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null
                || !host.matches("(?i)[a-z0-9.-]+\\.[a-z][a-z0-9-]*") || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new ImagePipeline.Failure(INVALID_SOURCE, "image_url_outside_https_allowlist");
        }
        var httpUrl = HttpUrl.parse(uri.toString());
        if (httpUrl == null || !host.equalsIgnoreCase(httpUrl.host()) || httpUrl.port() != 443
                || !Objects.equals(uri.getRawPath(), httpUrl.encodedPath())
                || !Objects.equals(uri.getRawQuery(), httpUrl.encodedQuery())) {
            throw new ImagePipeline.Failure(INVALID_SOURCE, "image_url_normalization_changes_target");
        }
        return host.toLowerCase(Locale.ROOT);
    }

    // Accept only broadly routable unicast space and exclude infrastructure/special-use networks.
    static boolean publicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99) || b == 168))
                    || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    || (a == 203 && b == 0 && c == 113)
                    || (a == 168 && b == 63 && c == 129 && d == 16));
        }
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
        return (a & 0xe0) == 0x20 && !(a == 0x20 && b == 0x01 && (c <= 1 || (c == 0x0d && d == 0xb8)))
                && !(a == 0x20 && b == 0x02) && !(a == 0x3f && b == 0xff);
    }

    private Duration retryAfter(String value) {
        if (value == null) {
            return Duration.ofMillis(400);
        }
        if (value.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException ex) {
                return Duration.ofSeconds(Long.MAX_VALUE);
            }
        }
        try {
            Duration delay = Duration.between(clock.instant(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException ex) {
            return Duration.ofMillis(400);
        }
    }

    private static Response pinnedGet(URI uri, InetAddress address) throws IOException {
        var pool = new ConnectionPool(0, 1, TimeUnit.SECONDS);
        var exchanged = new AtomicBoolean();
        var retryAfter = new AtomicReference<String>();
        var client = new OkHttpClient.Builder()
                .connectionPool(pool).proxy(Proxy.NO_PROXY)
                .dns(host -> {
                    if (!uri.getHost().equalsIgnoreCase(host)) {
                        throw new UnknownHostException(host);
                    }
                    return List.of(address);
                })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(3)).readTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(12))
                // Leave 503 retry decisions and header parsing to the outer retry policy.
                .addNetworkInterceptor(chain -> {
                    if (!exchanged.compareAndSet(false, true)) {
                        throw new IOException("automatic follow-up disabled");
                    }
                    var response = chain.proceed(chain.request());
                    if (response.code() == 503 && response.header("Retry-After") != null) {
                        retryAfter.set(response.header("Retry-After"));
                        return response.newBuilder().removeHeader("Retry-After").build();
                    }
                    return response;
                })
                .build();
        var response = client.newCall(new Request.Builder().url(uri.toString())
                .header("Accept", "image/jpeg, image/png").header("Accept-Encoding", "identity").build()).execute();
        return new Response() {
            public int status() { return response.code(); }
            public String header(String name) {
                return name.equalsIgnoreCase("Retry-After") && retryAfter.get() != null
                        ? retryAfter.get() : response.header(name);
            }
            public InputStream body() { return response.body().byteStream(); }
            public void close() { response.close(); pool.evictAll(); }
        };
    }
}
