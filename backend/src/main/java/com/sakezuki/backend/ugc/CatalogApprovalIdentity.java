package com.sakezuki.backend.ugc;

import com.sakezuki.imagecore.ImageTransform;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class CatalogApprovalIdentity {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{javaWhitespace}\\p{javaSpaceChar}]+");
    private static final String VERSION = "catalog-name-v1\n";

    private CatalogApprovalIdentity() {}

    static String name(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    static byte[] key(String normalized) {
        String digest = ImageTransform.sha256((VERSION + normalized).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().parseHex(digest);
    }
}
