package com.copybot.plugin.remote.s3;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.stream.Collectors;

/**
 * AWS Signature Version 4 of S3 requests, in the Authorization header (spec remote §7): the payload signed (its
 * SHA-256 in {@code x-amz-content-sha256}, never UNSIGNED-PAYLOAD). Neither the secret key nor a signature ever
 * leaves this class but in the Authorization value, which no message or log may hold.
 */
final class SigV4 {

    /** The SHA-256 of an empty payload. */
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKey;
    private final String secretKey;
    private final String region;

    /** The signing key of a day, derived once: {date, key}. */
    private volatile Object[] dayKey;

    SigV4(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    /** "20130524T000000Z", the value of {@code x-amz-date}. */
    static String amzDate(Instant time) {
        return AMZ_DATE.format(time);
    }

    /**
     * The value of the Authorization header.
     *
     * @param canonicalUri   the path of the request as sent (each name encoded by {@link #uriEncode}, '/' kept)
     * @param canonicalQuery the query as sent, from {@link #canonicalQuery}
     * @param headers        the headers signed, names in lower case: host, x-amz-date, x-amz-content-sha256 at least
     * @param payloadHash    the SHA-256 of the body, hexadecimal
     */
    String authorization(String method, String canonicalUri, String canonicalQuery, SortedMap<String, String> headers,
                         String payloadHash, Instant time) {
        String canonical = canonicalRequest(method, canonicalUri, canonicalQuery, headers, payloadHash);
        String signature = HEX.formatHex(hmac(signingKey(time), stringToSign(canonical, time)));
        return ALGORITHM + " Credential=" + accessKey + "/" + scope(time) + ",SignedHeaders="
                + String.join(";", headers.keySet()) + ",Signature=" + signature;
    }

    /** The canonical request: method, path, query, headers (trimmed values), their names, payload hash. */
    static String canonicalRequest(String method, String canonicalUri, String canonicalQuery,
                                   SortedMap<String, String> headers, String payloadHash) {
        StringBuilder canonical = new StringBuilder(method).append('\n').append(canonicalUri).append('\n')
                .append(canonicalQuery).append('\n');
        for (Map.Entry<String, String> header : headers.entrySet()) {
            canonical.append(header.getKey()).append(':')
                    .append(header.getValue().strip().replaceAll(" +", " ")).append('\n');
        }
        return canonical.append('\n').append(String.join(";", headers.keySet())).append('\n').append(payloadHash)
                .toString();
    }

    String stringToSign(String canonicalRequest, Instant time) {
        byte[] bytes = canonicalRequest.getBytes(StandardCharsets.UTF_8);
        return ALGORITHM + "\n" + amzDate(time) + "\n" + scope(time) + "\n" + sha256Hex(bytes, 0, bytes.length);
    }

    private String scope(Instant time) {
        return amzDate(time).substring(0, 8) + "/" + region + "/" + SERVICE + "/aws4_request";
    }

    private byte[] signingKey(Instant time) {
        String date = amzDate(time).substring(0, 8);
        Object[] cached = dayKey;
        if (cached != null && cached[0].equals(date)) {
            return (byte[]) cached[1];
        }
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, SERVICE);
        key = hmac(key, "aws4_request");
        dayKey = new Object[]{date, key};
        return key;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e); // every JDK has HmacSHA256
        }
    }

    /** The SHA-256 of a part of an array, hexadecimal. */
    static String sha256Hex(byte[] data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(data, offset, length);
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JDK has SHA-256
        }
    }

    /**
     * AWS's UriEncode: every byte of the UTF-8 form but A-Z a-z 0-9 - . _ ~ as %XX (upper case), a space as %20;
     * '/' kept when {@code keepSlash} (an object key in a path: S3 encodes it once, not twice).
     */
    static String uriEncode(String value, boolean keepSlash) {
        StringBuilder encoded = new StringBuilder(value.length() + 16);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/' && keepSlash) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    /** The query of a request, as sent and as signed: names and values encoded, sorted by name, "name=" when empty. */
    static String canonicalQuery(Map<String, String> parameters) {
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            pairs.add(new String[]{uriEncode(parameter.getKey(), false), uriEncode(parameter.getValue(), false)});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        return pairs.stream().map(p -> p[0] + "=" + p[1]).collect(Collectors.joining("&"));
    }

    /** Neither key. */
    @Override
    public String toString() {
        return "SigV4[" + region + "]";
    }
}
