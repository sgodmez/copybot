package com.copybot.plugin.remote.s3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A fake S3 server for the tests, on {@code com.sun.net.httpserver}: one bucket, in memory; each request's
 * signature recomputed from what arrived (path, query, signed headers, the body's SHA-256) and refused as S3 does
 * when it differs. ListObjectsV2 (pages, delimiter, url encoding), GET, HEAD, PUT, DELETE, multipart; failures
 * injected once.
 */
final class S3TestServer implements AutoCloseable {

    static final String ACCESS_KEY = "AKIDEXAMPLETEST";
    static final String SECRET_KEY = "s3cr3t/Key+ForTheTests";
    static final String REGION = "eu-west-3";
    static final String BUCKET = "photos";

    record StoredObject(byte[] data, Instant lastModified, Map<String, String> metadata) {
    }

    private record Upload(String key, Map<String, String> metadata, ConcurrentSkipListMap<Integer, byte[]> parts) {
    }

    /** A failure answered once to the first request it matches. */
    record Failure(Predicate<String> request, int status, String code, String message, Map<String, String> headers) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    /** The bucket's host name ("photos.s3.test"); null: path style. */
    private final String virtualHost;
    private final CountDownLatch closing = new CountDownLatch(1);

    final ConcurrentSkipListMap<String, StoredObject> objects = new ConcurrentSkipListMap<>();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    /** "GET /photos?list-type=2&..." for each request, as it arrived. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** The Host header of each request. */
    final List<String> hosts = new CopyOnWriteArrayList<>();
    final List<String> aborted = new CopyOnWriteArrayList<>();
    final List<String> unsigned = new CopyOnWriteArrayList<>();
    final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());
    /** The keys whose GET sends 10 bytes then nothing; the requests (method + key) answered only at the close. */
    final List<String> stalledDownloads = new CopyOnWriteArrayList<>();
    final List<String> stalledAnswers = new CopyOnWriteArrayList<>();
    /** The keys whose GET answers 503 with the start of an error, then nothing. */
    final List<String> stalledErrors = new CopyOnWriteArrayList<>();
    /** Listings answered with the keys as they are, encoding-type ignored (no EncodingType), as some servers do. */
    volatile boolean ignoresEncodingType;
    /** Listings saying EncodingType url but giving the keys as they are (a broken server). */
    volatile boolean rawKeysSaidEncoded;
    volatile int pageSize = 1000;
    volatile boolean anonymous;

    private S3TestServer(String virtualHost) throws IOException {
        this.virtualHost = virtualHost;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** The bucket in the path: http://127.0.0.1:port/photos/key. */
    static S3TestServer pathStyle() throws IOException {
        return new S3TestServer(null);
    }

    /** The bucket in the host name: http://photos.s3.test/key, reached through this server as a proxy. */
    static S3TestServer virtualHosted() throws IOException {
        return new S3TestServer(BUCKET + ".s3.test");
    }

    int port() {
        return server.getAddress().getPort();
    }

    void put(String key, String content, Instant lastModified) {
        objects.put(key, new StoredObject(content.getBytes(StandardCharsets.UTF_8), lastModified, Map.of()));
    }

    String content(String key) {
        return new String(objects.get(key).data(), StandardCharsets.UTF_8);
    }

    int openUploads() {
        return uploads.size();
    }

    void fail(Predicate<String> request, int status, String code, String message, Map<String, String> headers) {
        failures.add(new Failure(request, status, code, message, headers));
    }

    @Override
    public void close() {
        closing.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- requests

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            URI uri = exchange.getRequestURI();
            String method = exchange.getRequestMethod();
            String rawQuery = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            String line = method + " " + uri.getRawPath() + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
            requests.add(line);
            hosts.add(exchange.getRequestHeaders().getFirst("Host"));
            String refused = verifySignature(exchange, body, rawQuery);
            if (refused != null) {
                // as S3: the canonical request and the signature given come back too, which no message may show
                xml(exchange, 403, "<Error><Code>SignatureDoesNotMatch</Code><Message>The request signature we"
                        + " calculated does not match the signature you provided (" + refused + ")</Message>"
                        + "<AWSAccessKeyId>" + ACCESS_KEY + "</AWSAccessKeyId><StringToSign>AWS4-HMAC-SHA256"
                        + "</StringToSign><SignatureProvided>" + exchange.getRequestHeaders().getFirst("Authorization")
                        + "</SignatureProvided><CanonicalRequest>GET</CanonicalRequest></Error>");
                return;
            }
            Failure failure = takeFailure(line);
            if (failure != null) {
                error(exchange, failure.status(), failure.code(), failure.message(), failure.headers());
                return;
            }
            String bucket;
            String key;
            String path = uri.getPath();
            if (virtualHost == null) {
                String rest = path.substring(1);
                int slash = rest.indexOf('/');
                bucket = slash < 0 ? rest : rest.substring(0, slash);
                key = slash < 0 ? "" : rest.substring(slash + 1);
            } else {
                String host = exchange.getRequestHeaders().getFirst("Host");
                bucket = host.equals(virtualHost) ? BUCKET : host;
                key = path.substring(1);
            }
            if (!bucket.equals(BUCKET)) {
                error(exchange, 404, "NoSuchBucket", "The specified bucket does not exist", Map.of());
                return;
            }
            if (stalledAnswers.contains(method + " " + key)) {
                awaitClose();
                return;
            }
            Map<String, String> query = query(rawQuery);
            switch (method) {
                case "GET" -> {
                    if (key.isEmpty()) {
                        list(exchange, query);
                    } else {
                        get(exchange, key);
                    }
                }
                case "HEAD" -> head(exchange, key);
                case "PUT" -> {
                    if (query.containsKey("uploadId")) {
                        part(exchange, query, body);
                    } else {
                        StoredObject object = new StoredObject(body, now(), metadata(exchange));
                        objects.put(key, object);
                        exchange.getResponseHeaders().set("ETag", etag(body));
                        exchange.sendResponseHeaders(200, -1);
                    }
                }
                case "POST" -> {
                    if (query.containsKey("uploads")) {
                        String id = UUID.randomUUID().toString();
                        uploads.put(id, new Upload(key, metadata(exchange), new ConcurrentSkipListMap<>()));
                        xml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + BUCKET + "</Bucket><Key>"
                                + S3Xml.escape(key) + "</Key><UploadId>" + id + "</UploadId>"
                                + "</InitiateMultipartUploadResult>");
                    } else {
                        complete(exchange, key, query.get("uploadId"), body);
                    }
                }
                case "DELETE" -> {
                    if (query.containsKey("uploadId")) {
                        uploads.remove(query.get("uploadId"));
                        aborted.add(query.get("uploadId"));
                    } else {
                        objects.remove(key);
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> error(exchange, 405, "MethodNotAllowed", method, Map.of());
            }
        }
    }

    private Failure takeFailure(String line) {
        synchronized (failures) {
            for (Failure failure : failures) {
                if (failure.request().test(line)) {
                    failures.remove(failure);
                    return failure;
                }
            }
        }
        return null;
    }

    private void awaitClose() {
        try {
            closing.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** ListObjectsV2: keys after the token, the ones under a delimiter folded into a common prefix. */
    private void list(HttpExchange exchange, Map<String, String> query) throws IOException {
        if (!"2".equals(query.get("list-type"))) {
            error(exchange, 400, "InvalidArgument", "list-type", Map.of());
            return;
        }
        String prefix = query.getOrDefault("prefix", "");
        String delimiter = query.get("delimiter");
        boolean declared = "url".equals(query.get("encoding-type")) && !ignoresEncodingType;
        boolean url = declared && !rawKeysSaidEncoded;
        // the token: "K:" and the last key given, or "P:" and the last common prefix (its keys past too)
        String token = query.containsKey("continuation-token")
                ? new String(Base64.getDecoder().decode(query.get("continuation-token")), StandardCharsets.UTF_8) : null;
        String after = token == null ? null : token.substring(2);
        boolean afterPrefix = token != null && token.startsWith("P:");
        StringBuilder contents = new StringBuilder();
        StringBuilder prefixes = new StringBuilder();
        int count = 0;
        String last = null;
        boolean truncated = false;
        String folded = null;
        for (Map.Entry<String, StoredObject> entry : objects.tailMap(prefix).entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                break;
            }
            if (after != null && (key.compareTo(after) <= 0 || afterPrefix && key.startsWith(after))) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int cut = delimiter == null ? -1 : rest.indexOf(delimiter);
            String common = cut < 0 ? null : prefix + rest.substring(0, cut + delimiter.length());
            if (common != null && common.equals(folded)) {
                continue;
            }
            if (count == pageSize) {
                truncated = true;
                break;
            }
            count++;
            if (common != null) {
                folded = common;
                last = "P:" + common;
                prefixes.append("<CommonPrefixes><Prefix>").append(encode(common, url))
                        .append("</Prefix></CommonPrefixes>");
            } else {
                last = "K:" + key;
                StoredObject object = entry.getValue();
                contents.append("<Contents><Key>").append(encode(key, url)).append("</Key><LastModified>")
                        .append(DateTimeFormatter.ISO_INSTANT.format(object.lastModified()
                                .truncatedTo(ChronoUnit.MILLIS)))
                        .append("</LastModified><ETag>").append(S3Xml.escape(etag(object.data())))
                        .append("</ETag><Size>")
                        .append(object.data().length).append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            }
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>" + BUCKET + "</Name>"
                + "<Prefix>" + encode(prefix, url) + "</Prefix><KeyCount>" + count + "</KeyCount><MaxKeys>" + pageSize
                + "</MaxKeys>" + (declared ? "<EncodingType>url</EncodingType>" : "") + "<IsTruncated>" + truncated
                + "</IsTruncated>");
        if (truncated) {
            xml.append("<NextContinuationToken>")
                    .append(Base64.getEncoder().encodeToString(last.getBytes(StandardCharsets.UTF_8)))
                    .append("</NextContinuationToken>");
        }
        xml(exchange, 200, xml.append(contents).append(prefixes).append("</ListBucketResult>").toString());
    }

    private void get(HttpExchange exchange, String key) throws IOException {
        if (stalledErrors.contains(key)) {
            exchange.sendResponseHeaders(503, 200);
            OutputStream out = exchange.getResponseBody();
            out.write("<Error><Code>".getBytes(StandardCharsets.UTF_8));
            out.flush();
            awaitClose();
            return;
        }
        StoredObject object = objects.get(key);
        if (object == null) {
            error(exchange, 404, "NoSuchKey", "The specified key does not exist.", Map.of());
            return;
        }
        headers(exchange, object);
        if (stalledDownloads.contains(key)) {
            exchange.sendResponseHeaders(200, object.data().length);
            OutputStream out = exchange.getResponseBody();
            out.write(object.data(), 0, 10);
            out.flush();
            awaitClose();
            return;
        }
        exchange.sendResponseHeaders(200, object.data().length == 0 ? -1 : object.data().length);
        exchange.getResponseBody().write(object.data());
    }

    private void head(HttpExchange exchange, String key) throws IOException {
        StoredObject object = objects.get(key);
        if (object == null) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        headers(exchange, object);
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(object.data().length));
        exchange.sendResponseHeaders(200, -1);
    }

    private static void headers(HttpExchange exchange, StoredObject object) {
        exchange.getResponseHeaders().set("Last-Modified",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(object.lastModified().atOffset(ZoneOffset.UTC)));
        exchange.getResponseHeaders().set("ETag", etag(object.data()));
        object.metadata().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    }

    private void part(HttpExchange exchange, Map<String, String> query, byte[] body) throws IOException {
        Upload upload = uploads.get(query.get("uploadId"));
        if (upload == null) {
            error(exchange, 404, "NoSuchUpload", "The specified upload does not exist.", Map.of());
            return;
        }
        upload.parts().put(Integer.parseInt(query.get("partNumber")), body);
        exchange.getResponseHeaders().set("ETag", etag(body));
        exchange.sendResponseHeaders(200, -1);
    }

    /** CompleteMultipartUpload: the parts listed, in order, with their ETags, joined. */
    private void complete(HttpExchange exchange, String key, String id, byte[] body) throws IOException {
        Upload upload = id == null ? null : uploads.get(id);
        if (upload == null || !upload.key().equals(key)) {
            error(exchange, 404, "NoSuchUpload", "The specified upload does not exist.", Map.of());
            return;
        }
        Element root = S3Xml.parse(body);
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        int expected = 1;
        for (Element part : S3Xml.children(root, "Part")) {
            int number = Integer.parseInt(S3Xml.text(part, "PartNumber"));
            byte[] data = upload.parts().get(number);
            if (number != expected++ || data == null || !etag(data).equals(S3Xml.text(part, "ETag"))) {
                error(exchange, 400, "InvalidPart", "part " + number, Map.of());
                return;
            }
            joined.write(data);
        }
        if (expected - 1 != upload.parts().size()) {
            error(exchange, 400, "InvalidPart", "parts missing", Map.of());
            return;
        }
        uploads.remove(id);
        objects.put(key, new StoredObject(joined.toByteArray(), now(), upload.metadata()));
        xml(exchange, 200, "<CompleteMultipartUploadResult><Bucket>" + BUCKET + "</Bucket><Key>" + S3Xml.escape(key)
                + "</Key></CompleteMultipartUploadResult>");
    }

    // ---- signature

    /** Null when the request is signed as S3 checks it; else what is wrong. */
    private String verifySignature(HttpExchange exchange, byte[] body, String rawQuery) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null) {
            unsigned.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            return anonymous ? null : "not signed";
        }
        String hash = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (hash == null || !hash.equals(SigV4.sha256Hex(body, 0, body.length))) {
            return "payload hash";
        }
        String date = exchange.getRequestHeaders().getFirst("x-amz-date");
        if (date == null) {
            return "no date";
        }
        int signedAt = authorization.indexOf("SignedHeaders=");
        String names = authorization.substring(signedAt + "SignedHeaders=".length(),
                authorization.indexOf(',', signedAt));
        TreeMap<String, String> headers = new TreeMap<>();
        for (String name : names.split(";")) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value == null) {
                return "signed header missing: " + name;
            }
            headers.put(name, value);
        }
        if (!headers.containsKey("host") || !headers.containsKey("x-amz-date")
                || !headers.containsKey("x-amz-content-sha256")) {
            return "host, x-amz-date and x-amz-content-sha256 must be signed";
        }
        Instant time = java.time.LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                .toInstant(ZoneOffset.UTC);
        Map<String, String> query = new LinkedHashMap<>(query(rawQuery));
        String expected = new SigV4(ACCESS_KEY, SECRET_KEY, REGION).authorization(exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(), SigV4.canonicalQuery(query), headers, hash, time);
        return expected.equals(authorization) ? null : "signature";
    }

    /** The parameters of a query, decoded ("name" alone: an empty value). */
    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new TreeMap<>();
        if (rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            query.put(percentDecode(name), percentDecode(value));
        }
        return query;
    }

    private static String percentDecode(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    // ---- answers

    private static Map<String, String> metadata(HttpExchange exchange) {
        Map<String, String> metadata = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-meta-")) {
                metadata.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst());
            }
        });
        return metadata;
    }

    private static String encode(String text, boolean url) {
        return url ? URLEncoder.encode(text, StandardCharsets.UTF_8) : S3Xml.escape(text);
    }

    static String etag(byte[] data) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data)) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static void xml(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1); // as S3: no body
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    static void error(HttpExchange exchange, int status, String code, String message, Map<String, String> headers)
            throws IOException {
        headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        if (exchange.getRequestMethod().equals("HEAD") || code == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        xml(exchange, status, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>" + code + "</Code><Message>"
                + S3Xml.escape(message) + "</Message><RequestId>4442587FB7D0A2F9</RequestId></Error>");
    }

    Optional<StoredObject> object(String key) {
        return Optional.ofNullable(objects.get(key));
    }
}
