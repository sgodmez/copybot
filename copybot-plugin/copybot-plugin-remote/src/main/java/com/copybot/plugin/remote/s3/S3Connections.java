package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteText;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The checks and the reading of the connection fields shared by s3.read and s3.write. */
final class S3Connections {

    private static final Pattern REGION = Pattern.compile("[A-Za-z0-9_-]+");
    /** AWS's rules, with the legacy upper case and '_' (us-east-1) that MinIO also allows. */
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,254}");
    /** A bucket in a host name (virtual-hosted style): lower case, digits, '.' and '-', 3 to 63 characters. */
    private static final Pattern HOST_BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern ADDRESS = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}|\\[.*]");

    private S3Connections() {
    }

    /**
     * @throws IllegalArgumentException no region or bucket, or not valid; an endpoint not "http(s)://host[:port]"; an
     *                                  access key without secret key or the reverse; an address as endpoint, or a
     *                                  bucket that cannot be in a host name, without pathStyle
     */
    static void check(IPlugin plugin, String code, S3Settings settings) {
        if (settings.region() == null || settings.region().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.no-region", code));
        }
        if (!REGION.matcher(settings.region().strip()).matches()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.invalid-region", code,
                    settings.region()));
        }
        if (settings.bucket() == null || settings.bucket().isBlank()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.no-bucket", code));
        }
        if (!BUCKET.matcher(settings.bucket().strip()).matches()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.invalid-bucket", code,
                    settings.bucket()));
        }
        if (blank(settings.accessKey()) != blank(settings.secretKey())) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.keys", code));
        }
        URI endpoint = parse(settings);
        if (endpoint == null) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.endpoint", code,
                    settings.endpoint()));
        }
        if (!pathStyle(settings) && ADDRESS.matcher(endpoint.getHost()).matches()) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.address", code,
                    endpoint.getHost()));
        }
        if (!pathStyle(settings) && !fitsHostName(settings.bucket().strip(), endpoint)) {
            throw new IllegalArgumentException(RemoteText.message(plugin, "s3.error.virtual-bucket", code,
                    settings.bucket()));
        }
    }

    /** Whether the bucket can be the first name of the endpoint's host (virtual-hosted style): DNS rules. */
    private static boolean fitsHostName(String bucket, URI endpoint) {
        if (!HOST_BUCKET.matcher(bucket).matches() || bucket.contains("..")) {
            return false;
        }
        try {
            return new URI(endpoint.getScheme() + "://" + bucket + "." + endpoint.getRawAuthority()).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Files crossing the network in clear (an http:// endpoint; the secret key is never sent). */
    static List<String> warnings(IPlugin plugin, String code, S3Settings settings) {
        URI endpoint = parse(settings);
        if (endpoint != null && endpoint.getScheme().equalsIgnoreCase("http")) {
            return List.of(RemoteText.message(plugin, "s3.warning.clear-text", code));
        }
        return List.of();
    }

    /** "https://host[:port]" of a checked configuration; AWS's endpoint of the region when absent. */
    static URI endpoint(S3Settings settings) {
        URI endpoint = parse(settings);
        if (endpoint == null) {
            throw new IllegalArgumentException(settings.endpoint()); // checked by check() before
        }
        return endpoint;
    }

    static boolean pathStyle(S3Settings settings) {
        return Boolean.TRUE.equals(settings.pathStyle());
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The endpoint, scheme and host in lower case, without path; null when not valid. */
    private static URI parse(S3Settings settings) {
        String text = blank(settings.endpoint())
                ? "https://s3." + (settings.region() == null ? "" : settings.region().strip()) + ".amazonaws.com"
                : settings.endpoint().strip();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null
                    || uri.getRawUserInfo() != null || !(uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return new URI(scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
