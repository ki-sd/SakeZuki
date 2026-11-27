package com.sakezuki.tools.mapping;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ProductLinks {
    private static final Pattern PRODUCT_PATH = Pattern.compile("/sake/([a-z0-9]+(?:-[a-z0-9]+)*)-([1-9][0-9]*)/?");

    public record Link(String url, String pathKey, long numericId) {}
    public record RejectedLink(String href, String reason) {}
    public record Listing(List<Link> links, List<RejectedLink> rejectedLinks) {}

    private ProductLinks() {}

    public static Listing listing(String pageUrl, String html) {
        var base = sourceUri(pageUrl);
        var links = new ArrayList<Link>();
        var rejected = new ArrayList<RejectedLink>();
        for (Element anchor : Jsoup.parse(requireText(html, "missing_list_html")).select("a[href]")) {
            String href = anchor.attr("href");
            try {
                URI resolved = base.resolve(href);
                String path = resolved.getRawPath();
                if (path != null && PRODUCT_PATH.matcher(path).matches()) {
                    links.add(product(resolved));
                }
            } catch (IllegalArgumentException ex) {
                rejected.add(new RejectedLink(href, ex.getMessage()));
            }
        }
        return new Listing(List.copyOf(links), List.copyOf(rejected));
    }

    public static Link detail(String pageUrl, String html) {
        var base = sourceUri(pageUrl);
        Link requested = product(base);
        if (html == null) {
            return requested;
        }
        var canonicals = Jsoup.parse(requireText(html, "empty_detail_html")).select("link[rel~=canonical]");
        for (Element canonical : canonicals) {
            Link declared = product(base.resolve(requireText(canonical.attr("href"), "missing_canonical_href")));
            if (!requested.pathKey().equals(declared.pathKey())) {
                throw new IllegalArgumentException("canonical_conflict");
            }
        }
        return requested;
    }

    private static Link product(URI uri) {
        var source = sourceUri(uri.toString());
        var matcher = PRODUCT_PATH.matcher(source.getRawPath());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid_product_path");
        }
        try {
            String pathKey = matcher.group(1) + "-" + matcher.group(2);
            return new Link(source.toString().split("[?#]", 2)[0], pathKey, Long.parseLong(matcher.group(2)));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("invalid_numeric_id", ex);
        }
    }

    private static URI sourceUri(String value) {
        URI uri = URI.create(requireText(value, "missing_url"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"sake-guide.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("invalid_source_origin");
        }
        return uri;
    }

    private static String requireText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(reason);
        }
        return value;
    }
}
