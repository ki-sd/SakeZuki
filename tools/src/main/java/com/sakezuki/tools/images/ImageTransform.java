package com.sakezuki.tools.images;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import static com.sakezuki.tools.images.ImagePipeline.Status.*;

final class ImageTransform {
    static final String VERSION = "image-v1";
    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    static final int MAX_EDGE = 8192;
    static final long MAX_PIXELS = 16_000_000;
    static final int TARGET_EDGE = 1200;
    private static final Set<String> PNG_RASTER_CHUNKS = Set.of("IHDR", "PLTE", "tRNS", "IDAT", "IEND");
    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public record Info(String mimeType, int byteLength, int width, int height, String sha256) {}
    record Output(Info original, Info transformed, byte[] bytes) {}

    private ImageTransform() {}

    static String requireMime(String header) throws ImagePipeline.Failure {
        String mime = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!mime.equals(PNG) && !mime.equals(JPEG)) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "unsupported_image_content_type");
        }
        return mime;
    }

    static Output convert(String mime, byte[] bytes, Set<String> placeholderHashes) throws ImagePipeline.Failure {
        mime = requireMime(mime);
        if (bytes.length > SecureImageDownload.MAX_BYTES) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "download_byte_limit");
        }
        byte[] decodeBytes = validateContainer(mime, bytes);
        String sourceHash = sha256(bytes);
        if (placeholderHashes.contains(sourceHash)) {
            throw new ImagePipeline.Failure(IMAGE_ABSENT, "known_placeholder_sha256");
        }
        BufferedImage image = decode(mime, decodeBytes);
        var original = new Info(mime, bytes.length, image.getWidth(), image.getHeight(), sourceHash);
        try {
            BufferedImage resized = resize(image, mime.equals(PNG) && image.getColorModel().hasAlpha());
            byte[] output = encode(resized, mime);
            if (output.length == 0 || output.length > SecureImageDownload.MAX_BYTES) {
                throw new IOException("converted image exceeds size limit");
            }
            return new Output(original,
                    new Info(mime, output.length, resized.getWidth(), resized.getHeight(), sha256(output)), output);
        } catch (IOException | RuntimeException ex) {
            throw new ImagePipeline.Failure(TRANSFORM_FAILED, "image_encoding_failed");
        }
    }

    private static byte[] validateContainer(String mime, byte[] bytes) throws ImagePipeline.Failure {
        if (mime.equals(JPEG)) {
            if (bytes.length < 5 || bytes[0] != (byte) 0xff || bytes[1] != (byte) 0xd8
                    || bytes[2] != (byte) 0xff || bytes[bytes.length - 2] != (byte) 0xff
                    || bytes[bytes.length - 1] != (byte) 0xd9) {
                throw new ImagePipeline.Failure(INVALID_IMAGE, "invalid_jpeg_signature_or_end");
            }
            return bytes;
        }
        var data = ByteBuffer.wrap(bytes);
        for (byte expected : PNG_HEADER) {
            if (!data.hasRemaining() || data.get() != expected) {
                throw new ImagePipeline.Failure(INVALID_IMAGE, "invalid_png_signature");
            }
        }
        // Strip nonessential chunks before the decoder sees input, including compressed metadata.
        var raster = new ByteArrayOutputStream();
        raster.writeBytes(PNG_HEADER);
        boolean first = true;
        boolean imageData = false;
        int chunks = 0;
        while (data.remaining() >= 12 && ++chunks <= 10_000) {
            int length = data.getInt();
            if (length < 0 || length > data.remaining() - 8) {
                break;
            }
            int start = data.position();
            String type = new String(bytes, start, 4, StandardCharsets.US_ASCII);
            if ((first ? !type.equals("IHDR") || length != 13 : type.equals("IHDR")) || type.equals("acTL")
                    || type.equals("fcTL") || type.equals("fdAT")) {
                break;
            }
            first = false;
            var crc = new CRC32();
            crc.update(bytes, start, 4 + length);
            data.position(start + 4 + length);
            if (Integer.toUnsignedLong(data.getInt()) != crc.getValue()) {
                break;
            }
            imageData |= type.equals("IDAT");
            if (PNG_RASTER_CHUNKS.contains(type)) {
                raster.write(bytes, start - 4, length + 12);
            } else if ((bytes[start] & 0x20) == 0) {
                break; // Unknown critical chunk.
            }
            if (type.equals("IEND") && imageData && length == 0 && !data.hasRemaining()) {
                return raster.toByteArray();
            }
        }
        throw new ImagePipeline.Failure(INVALID_IMAGE, "invalid_or_animated_png");
    }

    private static BufferedImage decode(String mime, byte[] bytes) throws ImagePipeline.Failure {
        var readers = ImageIO.getImageReadersByFormatName(mime.equals(PNG) ? "png" : "jpeg");
        if (!readers.hasNext()) {
            throw new ImagePipeline.Failure(TRANSFORM_FAILED, "image_reader_unavailable");
        }
        ImageReader reader = readers.next();
        var warning = new AtomicBoolean();
        reader.addIIOReadWarningListener((src, message) -> warning.set(true));
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE || (long) width * height > MAX_PIXELS) {
                throw new ImagePipeline.Failure(INVALID_IMAGE, "image_resolution_limit");
            }
            BufferedImage image = reader.read(0);
            if (image == null || warning.get() || image.getWidth() != width || image.getHeight() != height) {
                throw new ImagePipeline.Failure(INVALID_IMAGE, "image_decode_warning_or_mismatch");
            }
            return image;
        } catch (IOException | RuntimeException ex) {
            throw new ImagePipeline.Failure(INVALID_IMAGE, "image_decode_failed");
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage resize(BufferedImage source, boolean alpha) {
        double ratio = Math.min(1.0, TARGET_EDGE / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String mime) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(mime.equals(PNG) ? "png" : "jpeg");
        if (!writers.hasNext()) {
            throw new IOException("image writer unavailable");
        }
        var writer = writers.next();
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            var options = writer.getDefaultWriteParam();
            if (mime.equals(JPEG)) {
                options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                options.setCompressionQuality(0.86f);
            }
            writer.write(null, new IIOImage(image, null, null), options);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required by Java", ex);
        }
    }
}
