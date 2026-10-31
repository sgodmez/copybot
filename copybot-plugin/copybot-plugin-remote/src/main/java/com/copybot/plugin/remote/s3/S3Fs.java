package com.copybot.plugin.remote.s3;

import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.remote.core.RemoteEntry;
import com.copybot.plugin.remote.core.RemoteFs;
import com.copybot.plugin.remote.core.RemotePaths;
import com.copybot.plugin.remote.core.RemoteText;
import org.w3c.dom.Element;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PushbackInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A "connection" to an S3 bucket (spec remote §7): an HTTP/1.1 client of its own, every request signed (SigV4,
 * {@link SigV4}) unless anonymous. Paths are keys ('/' at their start ignored); folders are key prefixes, so
 * {@link #mkdirs} does nothing, and an upload is a PUT under the final key ({@link #atomicUpload}), with the item's
 * date as {@code x-amz-meta-mtime} (seconds since 1970, fraction allowed, as rclone writes it) that {@link #stat}
 * (HEAD) gives back; a listing gives LastModified, the date of the upload.
 * <p>
 * The content of an upload is read on the calling thread into a buffer, its SHA-256 signed: a single PUT up to
 * {@link Tuning#multipartThreshold} (16 MiB), else a multipart upload of {@link Tuning#partSize} parts (16 MiB, more
 * when 10,000 parts would not hold the size), aborted when it fails or is cancelled. The buffer is the connection's,
 * reused by its uploads and freed when it is closed: up to one part in memory per connection, maxConnections x 16 MiB
 * for a step (64 MiB with the default 4).
 * <p>
 * Delays: 20 s to connect; a request, an answer or a download giving no progress for 60 s is abandoned (a part may
 * take longer as long as it is sent). Redirects are never followed: a bucket of another region is named in the
 * failure. An interrupt abandons the request ({@link InterruptedIOException}, the interrupt kept).
 */
final class S3Fs implements RemoteFs {

    static final int MIB = 1 << 20;
    static final int MAX_PARTS = 10_000;
    private static final int CHUNK = 64 * 1024;
    private static final int ERROR_BODY_LIMIT = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final System.Logger LOGGER = System.getLogger(S3Fs.class.getName());

    /** Watches the downloads: closes one whose server sends nothing for the timeout. */
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /** The sizes and delays, other in the tests only. */
    record Tuning(int multipartThreshold, int partSize, Duration timeout, ProxySelector proxy, Clock clock) {
        static final Tuning DEFAULT = new Tuning(16 * MIB, 16 * MIB, Duration.ofSeconds(60), null,
                Clock.systemUTC());
    }

    private final IPlugin plugin;
    private final URI endpoint;
    private final String region;
    private final String bucket;
    private final boolean pathStyle;
    /** Null: anonymous requests. */
    private final SigV4 signer;
    private final Tuning tuning;
    private final HttpClient client;
    /**
     * The buffer of the uploads, made by the first one and reused by the next ones (one at a time: the connection is
     * used by one thread at a time); it keeps the size of the largest part read, until the connection is closed.
     */
    private PartBuffer buffer;

    private S3Fs(IPlugin plugin, URI endpoint, String region, String bucket, boolean pathStyle, SigV4 signer,
                 Tuning tuning, HttpClient client) {
        this.plugin = plugin;
        this.endpoint = endpoint;
        this.region = region;
        this.bucket = bucket;
        this.pathStyle = pathStyle;
        this.signer = signer;
        this.tuning = tuning;
        this.client = client;
    }

    /** A client for the bucket of a checked configuration; nothing is sent yet. */
    static S3Fs connect(S3Settings settings, IPlugin plugin, Tuning tuning) {
        String region = settings.region().strip();
        SigV4 signer = S3Connections.blank(settings.accessKey()) ? null
                : new SigV4(settings.accessKey().strip(), settings.secretKey().strip(), region);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT);
        if (tuning.proxy() != null) {
            builder.proxy(tuning.proxy());
        }
        return new S3Fs(plugin, S3Connections.endpoint(settings), region, settings.bucket().strip(),
                S3Connections.pathStyle(settings), signer, tuning, builder.build());
    }

    // ---- RemoteFs

    @Override
    public List<RemoteEntry> list(String folder) throws IOException {
        List<RemoteEntry> entries = new ArrayList<>();
        listPages(folder, true, entries::add);
        return entries;
    }

    /** One listing of the keys under the prefix, without delimiter, given page by page. */
    @Override
    public boolean listTree(String folder, Consumer<RemoteEntry> files) throws IOException {
        listPages(folder, false, files);
        return true;
    }

    @Override
    public InputStream open(String path) throws IOException {
        String key = key(path);
        Progress progress = new Progress();
        HttpRequest request = request("GET", key, Map.of(), Map.of(), null, 0, progress);
        HttpResponse<InputStream> response = exchange(request,
                info -> counted(HttpResponse.BodySubscribers.ofInputStream(), progress), progress);
        InputStream body = new DownloadStream(response.body(), progress, display(key));
        if (response.statusCode() != 200) {
            byte[] error;
            try (body) { // watched too: a server stalling in its error is given up
                error = body.readNBytes(ERROR_BODY_LIMIT);
            }
            throw failure(response.statusCode(), response.headers(), error, display(key));
        }
        return body;
    }

    @Override
    public Optional<RemoteEntry> stat(String path) throws IOException {
        String key = key(path);
        Answer answer = send("HEAD", key, Map.of(), Map.of(), null, 0);
        if (answer.status() == 404) {
            return Optional.empty();
        }
        if (answer.status() != 200) {
            throw failure(answer, display(key));
        }
        long size = answer.headers().firstValueAsLong("content-length").orElse(0);
        Instant date = answer.headers().firstValue("x-amz-meta-mtime").map(S3Fs::parseMtime).orElse(null);
        if (date == null) {
            date = answer.headers().firstValue("last-modified").map(S3Fs::parseHttpDate).orElse(null);
        }
        return Optional.of(RemoteEntry.file(RemotePaths.name(key), size, date));
    }

    /** Nothing: an S3 "folder" is the prefix of its keys. */
    @Override
    public void mkdirs(String folder) {
    }

    @Override
    public void upload(String path, InputStream content, long size, Instant lastModified) throws IOException {
        String key = key(path);
        Map<String, String> metadata = lastModified == null ? Map.of()
                : Map.of("x-amz-meta-mtime", mtime(lastModified));
        int threshold = tuning.multipartThreshold();
        PushbackInputStream in = new PushbackInputStream(content, 1);
        if (buffer == null) {
            buffer = new PartBuffer(size >= 0 ? (int) Math.min(size + 1, threshold) : Math.min(CHUNK, threshold));
        }
        int length = buffer.fill(in, threshold);
        if (length < threshold || atEnd(in)) {
            Answer answer = send("PUT", key, Map.of(), metadata, buffer.data, length);
            if (answer.status() != 200) {
                throw failure(answer, display(key));
            }
            return;
        }
        multipart(key, in, buffer, length, size, metadata);
    }

    /** The upload buffer's array, null when none (tests). */
    byte[] buffer() {
        return buffer == null ? null : buffer.data;
    }

    /** Never called: the uploads are atomic. */
    @Override
    public void rename(String from, String to, boolean replace) throws IOException {
        throw new IOException("S3 has no rename");
    }

    @Override
    public void delete(String path) throws IOException {
        String key = key(path);
        Answer answer = send("DELETE", key, Map.of(), Map.of(), null, 0);
        if (answer.status() != 204 && answer.status() != 200 && answer.status() != 404) {
            throw failure(answer, display(key));
        }
    }

    @Override
    public boolean atomicUpload() {
        return true;
    }

    @Override
    public boolean keepsLastModified() {
        return true;
    }

    /** Ends the requests under way and closes the client's connections. */
    @Override
    public void close() {
        client.shutdownNow();
        buffer = null;
    }

    // ---- listing

    /**
     * ListObjectsV2 of the folder's prefix, page by page (continuation-token), keys asked url-encoded (a key may hold
     * characters XML cannot carry) and decoded when the answer says so (EncodingType). With delimiter: the files of
     * the folder and its sub-folders (common prefixes); without: every key under it, named relative to the folder. A
     * key ending with '/' (a "folder" made by a console) is not a file.
     */
    private void listPages(String folder, boolean delimited, Consumer<RemoteEntry> sink) throws IOException {
        String prefix = key(folder);
        if (!prefix.isEmpty()) {
            prefix += "/";
        }
        String token = null;
        boolean truncated;
        do {
            Map<String, String> query = new TreeMap<>(Map.of("list-type", "2", "prefix", prefix,
                    "encoding-type", "url"));
            if (delimited) {
                query.put("delimiter", "/");
            }
            if (token != null) {
                query.put("continuation-token", token);
            }
            Answer answer = send("GET", "", query, Map.of(), null, 0);
            if (answer.status() != 200) {
                throw failure(answer, display(folder));
            }
            Element root = xml(answer.body());
            boolean encoded = "url".equals(S3Xml.text(root, "EncodingType"));
            for (Element contents : S3Xml.children(root, "Contents")) {
                String key = decode(S3Xml.text(contents, "Key"), encoded);
                if (key == null || !key.startsWith(prefix) || key.endsWith("/")) {
                    continue;
                }
                String name = key.substring(prefix.length());
                if (delimited && name.contains("/")) {
                    continue;
                }
                sink.accept(RemoteEntry.file(name, parseSize(S3Xml.text(contents, "Size")),
                        parseIso(S3Xml.text(contents, "LastModified"))));
            }
            for (Element common : S3Xml.children(root, "CommonPrefixes")) {
                String sub = decode(S3Xml.text(common, "Prefix"), encoded);
                if (sub != null && sub.startsWith(prefix) && sub.endsWith("/")) {
                    String name = sub.substring(prefix.length(), sub.length() - 1);
                    if (!name.isEmpty() && !name.contains("/")) {
                        sink.accept(RemoteEntry.folder(name));
                    }
                }
            }
            truncated = "true".equals(S3Xml.text(root, "IsTruncated"));
            token = S3Xml.text(root, "NextContinuationToken");
            if (truncated && (token == null || token.isEmpty())) {
                throw new IOException(message("s3.error.answer", "IsTruncated without NextContinuationToken"));
            }
        } while (truncated);
    }

    // ---- upload

    /** Whether the stream has no byte left; a byte read is put back. */
    private static boolean atEnd(PushbackInputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            return true;
        }
        in.unread(b);
        return false;
    }

    /**
     * Create, the parts (the first one already read), Complete; Abort when anything fails or is cancelled, so that no
     * part is left in the bucket (stored, and billed, until aborted).
     */
    private void multipart(String key, PushbackInputStream in, PartBuffer buffer, int firstLength, long size,
                           Map<String, String> metadata) throws IOException {
        int partSize = partSize(size, key);
        Answer created = send("POST", key, Map.of("uploads", ""), metadata, null, 0);
        if (created.status() != 200) {
            throw failure(created, display(key));
        }
        String uploadId = S3Xml.text(xml(created.body()), "UploadId");
        if (uploadId == null || uploadId.isBlank()) {
            throw new IOException(message("s3.error.answer", "no UploadId"));
        }
        boolean completed = false;
        try {
            List<String> etags = new ArrayList<>();
            etags.add(uploadPart(key, uploadId, 1, buffer, firstLength));
            int length;
            while ((length = buffer.fill(in, partSize)) > 0) {
                if (etags.size() == MAX_PARTS) {
                    throw new IOException(message("s3.error.too-large", display(key), MAX_PARTS));
                }
                etags.add(uploadPart(key, uploadId, etags.size() + 1, buffer, length));
                if (length < partSize) {
                    break;
                }
            }
            StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
            for (int i = 0; i < etags.size(); i++) {
                xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
                        .append(S3Xml.escape(etags.get(i))).append("</ETag></Part>");
            }
            byte[] body = xml.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
            Answer answer = send("POST", key, Map.of("uploadId", uploadId), Map.of(), body, body.length);
            // an error may come with 200 once the parts are being joined
            if (answer.status() != 200 || isError(answer.body())) {
                throw failure(answer, display(key));
            }
            completed = true;
        } finally {
            if (!completed) {
                abortQuietly(key, uploadId);
            }
        }
    }

    /** The parts' size of an upload, {@link #partSize(long, int)} as an int. */
    private int partSize(long size, String key) throws IOException {
        long partSize = partSize(size, tuning.partSize());
        if (partSize > Integer.MAX_VALUE - CHUNK) {
            throw new IOException(message("s3.error.too-large", display(key), MAX_PARTS));
        }
        return (int) partSize;
    }

    /**
     * The parts' size: {@code tuned}, more (in whole MiB) when the parts of a known size would exceed
     * {@value #MAX_PARTS} (from about 156 GiB with 16 MiB parts).
     */
    static long partSize(long size, int tuned) {
        if (size > (long) tuned * (MAX_PARTS - 1)) { // the first part may be smaller: the others carry the size
            long needed = (size + MAX_PARTS - 2) / (MAX_PARTS - 1);
            return (needed + MIB - 1) / MIB * MIB;
        }
        return tuned;
    }

    /** UploadPart; its ETag. */
    private String uploadPart(String key, String uploadId, int number, PartBuffer buffer, int length)
            throws IOException {
        Answer answer = send("PUT", key, Map.of("partNumber", Integer.toString(number), "uploadId", uploadId),
                Map.of(), buffer.data, length);
        if (answer.status() != 200) {
            throw failure(answer, display(key));
        }
        return answer.headers().firstValue("etag")
                .orElseThrow(() -> new IOException(message("s3.error.answer", "no ETag")));
    }

    /** AbortMultipartUpload, on an interrupted thread too (the interrupt set aside, then restored); never throws. */
    private void abortQuietly(String key, String uploadId) {
        boolean interrupted = Thread.interrupted();
        try {
            Answer answer = send("DELETE", key, Map.of("uploadId", uploadId), Map.of(), null, 0);
            if (answer.status() != 204 && answer.status() != 200 && answer.status() != 404) {
                LOGGER.log(System.Logger.Level.WARNING, "s3: the multipart upload of " + display(key)
                        + " is not aborted (" + failure(answer, display(key)).getMessage() + ")");
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(System.Logger.Level.WARNING, "s3: the multipart upload of " + display(key)
                    + " is not aborted (" + RemoteText.detail(e) + ")");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** "1714558530" or "1714558530.25": seconds since 1970. */
    static String mtime(Instant date) {
        return BigDecimal.valueOf(date.getEpochSecond()).add(BigDecimal.valueOf(date.getNano(), 9))
                .stripTrailingZeros().toPlainString();
    }

    /** The date of {@link #mtime}, a fraction of any precision allowed; null when not a number. */
    static Instant parseMtime(String text) {
        try {
            BigDecimal value = new BigDecimal(text.strip());
            BigDecimal seconds = value.setScale(0, RoundingMode.FLOOR);
            long nanos = value.subtract(seconds).movePointRight(9).setScale(0, RoundingMode.FLOOR).longValue();
            return Instant.ofEpochSecond(seconds.longValueExact(), nanos);
        } catch (NumberFormatException | ArithmeticException | DateTimeException e) {
            return null;
        }
    }

    private static Instant parseHttpDate(String text) {
        try {
            return ZonedDateTime.parse(text.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Instant parseIso(String text) throws IOException {
        try {
            return text == null ? null : Instant.parse(text.strip());
        } catch (DateTimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static long parseSize(String text) throws IOException {
        try {
            return Long.parseLong(text == null ? "" : text.strip());
        } catch (NumberFormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * A key or prefix of the answer: url-decoded (a space as '+') only when the answer says it is encoded, as a server
     * may ignore encoding-type and give the keys as they are.
     */
    private String decode(String text, boolean encoded) throws IOException {
        if (text == null || !encoded) {
            return text;
        }
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IOException(message("s3.error.answer", RemoteText.detail(e)), e);
        }
    }

    // ---- requests

    /** An answer read whole (S3's XML, or nothing). */
    private record Answer(int status, HttpHeaders headers, byte[] body) {
    }

    private Answer send(String method, String key, Map<String, String> query, Map<String, String> amzHeaders,
                        byte[] body, int length) throws IOException {
        Progress progress = new Progress();
        HttpRequest request = request(method, key, query, amzHeaders, body, length, progress);
        HttpResponse<byte[]> response = exchange(request,
                info -> counted(HttpResponse.BodySubscribers.ofByteArray(), progress), progress);
        return new Answer(response.statusCode(), response.headers(), response.body());
    }

    /** The request, signed: host, x-amz-date, x-amz-content-sha256 (the body's) and the x-amz-* headers given. */
    private HttpRequest request(String method, String key, Map<String, String> query, Map<String, String> amzHeaders,
                                byte[] body, int length, Progress progress) throws IOException {
        String canonicalQuery = SigV4.canonicalQuery(query);
        URI uri = uri(key, canonicalQuery);
        String hash = body == null ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(body, 0, length);
        Instant now = tuning.clock().instant();
        TreeMap<String, String> signed = new TreeMap<>(amzHeaders);
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-content-sha256", hash);
        signed.put("x-amz-date", SigV4.amzDate(now));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : new ArrayBody(body, length, progress));
        signed.forEach((name, value) -> {
            if (!name.equals("host")) { // set by the client, from the URI, as hostHeader gives it
                builder.header(name, value);
            }
        });
        if (signer != null) {
            builder.header("Authorization", signer.authorization(method, uri.getRawPath(), canonicalQuery, signed,
                    hash, now));
        }
        return builder.build();
    }

    /**
     * The bucket in the host name ("bucket.host/key") or in the path ("host/bucket/key"); the key encoded once.
     *
     * @throws IOException the bucket cannot be put in a host name (a configuration not checked by
     *                     {@link S3Connections#check})
     */
    private URI uri(String key, String canonicalQuery) throws IOException {
        String bucketPath = "/" + SigV4.uriEncode(bucket, false);
        String path = pathStyle
                ? (key.isEmpty() ? bucketPath : bucketPath + "/" + SigV4.uriEncode(key, true))
                : "/" + SigV4.uriEncode(key, true);
        String authority = pathStyle ? endpoint.getRawAuthority() : bucket + "." + endpoint.getRawAuthority();
        try {
            URI uri = new URI(endpoint.getScheme() + "://" + authority + path
                    + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));
            if (uri.getHost() != null) {
                return uri;
            }
        } catch (URISyntaxException e) {
            // the bucket's name, the rest being encoded
        }
        throw new IOException(message("s3.error.virtual-host", bucket));
    }

    /** The Host header the client sends: the port only when not the scheme's. */
    static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == (uri.getScheme().equals("https") ? 443 : 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /**
     * Sends a request and waits for its answer (its body too, unless streamed), abandoned when nothing moves for the
     * timeout: neither the request's body nor the answer.
     *
     * @throws InterruptedIOException interrupted, the interrupt kept
     * @throws IOException            the network failed, or nothing moved for the timeout
     */
    private <T> HttpResponse<T> exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler, Progress progress)
            throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("interrupted");
        }
        String server = request.uri().getRawAuthority();
        CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, handler);
        long timeout = tuning.timeout().toNanos();
        long poll = Math.clamp(tuning.timeout().toMillis() / 4, 10, 1000);
        try {
            while (true) {
                try {
                    return future.get(poll, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (progress.idleNanos() > timeout) {
                        future.cancel(true);
                        throw new HttpTimeoutException(message("s3.error.stalled", server,
                                tuning.timeout().toSeconds()));
                    }
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException(message("s3.error.network", server, RemoteText.detail(cause)), cause);
        }
    }

    // ---- failures

    private static boolean isError(byte[] body) {
        if (body.length == 0) {
            return false;
        }
        try {
            return S3Xml.localName(S3Xml.parse(body)).equals("Error");
        } catch (IOException e) {
            return false; // not XML: the status decides
        }
    }

    private IOException failure(Answer answer, String display) {
        return failure(answer.status(), answer.headers(), answer.body(), display);
    }

    /**
     * The failure of an answer, from its S3 error (Code, Message: never the rest, which may hold the canonical
     * request), in the user's words where it is common; the other region of the bucket when the server says it.
     */
    private IOException failure(int status, HttpHeaders headers, byte[] body, String display) {
        String code = null;
        String text = null;
        String bucketRegion = headers.firstValue("x-amz-bucket-region").orElse(null);
        if (body != null && body.length > 0) {
            try {
                Element error = S3Xml.parse(body);
                code = clean(S3Xml.text(error, "Code"));
                text = clean(S3Xml.text(error, "Message"));
                if (bucketRegion == null) {
                    bucketRegion = clean(S3Xml.text(error, "Region"));
                }
            } catch (IOException e) {
                // not XML (a proxy's page): the status alone
            }
        }
        if (bucketRegion != null && !bucketRegion.isEmpty() && !bucketRegion.equals(region)
                && (status == 301 || status == 307 || status == 400 || status == 403)) {
            return new IOException(message("s3.error.region", bucket, bucketRegion, region));
        }
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            return new IOException(message("s3.error.redirect", status));
        }
        String key = switch (code == null ? "" : code) {
            case "NoSuchBucket" -> "s3.error.bucket-missing";
            case "NoSuchKey" -> "s3.error.not-found";
            case "AccessDenied" -> "s3.error.denied";
            case "InvalidAccessKeyId" -> "s3.error.access-key";
            case "SignatureDoesNotMatch" -> "s3.error.signature";
            case "RequestTimeTooSkewed" -> "s3.error.clock";
            case "" -> status == 403 ? "s3.error.denied" : status == 404 ? "s3.error.not-found" : null;
            default -> null;
        };
        if (key != null) {
            return new IOException(message(key, display, bucket));
        }
        return new IOException(message("s3.error.reply", status, code == null ? "-" : code,
                text == null ? "" : text));
    }

    /** A text of the server, for a message: one line, at most 200 characters. */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String line = text.replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private Element xml(byte[] body) throws IOException {
        try {
            return S3Xml.parse(body);
        } catch (IOException e) {
            throw new IOException(message("s3.error.answer", RemoteText.detail(e)), e);
        }
    }

    // ---- names

    /** The key of a path: without the '/' at its start. */
    static String key(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return path.substring(start);
    }

    /** "s3://bucket/key", for the user. */
    private String display(String path) {
        return RemoteText.display("s3", bucket, key(path));
    }

    private String message(String key, Object... args) {
        return RemoteText.message(plugin, key, args);
    }

    // ---- progress, bodies, watchdog

    /** When a request last moved: a part of its body sent, of its answer received, a read of a download begun. */
    private static final class Progress {
        private final AtomicLong last = new AtomicLong(System.nanoTime());

        void touch() {
            last.set(System.nanoTime());
        }

        long idleNanos() {
            return System.nanoTime() - last.get();
        }
    }

    /** An answer's body, each part received telling the progress. */
    private static <T> HttpResponse.BodySubscriber<T> counted(HttpResponse.BodySubscriber<T> inner, Progress progress) {
        return new HttpResponse.BodySubscriber<>() {
            @Override
            public CompletableFuture<T> getBody() {
                return inner.getBody().toCompletableFuture();
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                inner.onSubscribe(subscription);
            }

            @Override
            public void onNext(List<ByteBuffer> item) {
                progress.touch();
                inner.onNext(item);
            }

            @Override
            public void onError(Throwable throwable) {
                inner.onError(throwable);
            }

            @Override
            public void onComplete() {
                inner.onComplete();
            }
        };
    }

    /**
     * A request's body from a part of an array, in 64 KiB slices of it (no copy, unlike
     * {@code BodyPublishers.ofByteArray}), each slice taken telling the progress. The array must not change until
     * the answer is received.
     */
    private static final class ArrayBody implements HttpRequest.BodyPublisher {
        private final byte[] data;
        private final int length;
        private final Progress progress;

        ArrayBody(byte[] data, int length, Progress progress) {
            this.data = data;
            this.length = length;
            this.progress = progress;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicLong demand = new AtomicLong();
                private final AtomicInteger draining = new AtomicInteger();
                private volatile boolean cancelled;
                private int position;
                private boolean done;

                @Override
                public void request(long n) {
                    if (n <= 0) {
                        cancelled = true;
                        subscriber.onError(new IllegalArgumentException("non-positive request"));
                        return;
                    }
                    demand.getAndAccumulate(n, (current, add) -> current + add < 0 ? Long.MAX_VALUE : current + add);
                    drain();
                }

                /** One thread at a time delivers; a request made from onNext is served by the loop. */
                private void drain() {
                    if (draining.getAndIncrement() != 0) {
                        return;
                    }
                    do {
                        while (!cancelled && position < length && demand.get() > 0) {
                            int size = Math.min(CHUNK, length - position);
                            ByteBuffer slice = ByteBuffer.wrap(data, position, size).slice();
                            position += size;
                            demand.decrementAndGet();
                            progress.touch();
                            subscriber.onNext(slice);
                        }
                        if (!cancelled && !done && position >= length) {
                            done = true;
                            subscriber.onComplete();
                        }
                    } while (draining.decrementAndGet() != 0);
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }
    }

    /**
     * A download: a read waiting for the timeout without a byte from the server closes the answer, the read failing
     * with a message saying so.
     */
    private final class DownloadStream extends FilterInputStream {
        private final Progress progress;
        private final String display;
        private final ScheduledFuture<?> watch;
        private volatile boolean reading;
        private volatile boolean stalled;

        DownloadStream(InputStream in, Progress progress, String display) {
            super(in);
            this.progress = progress;
            this.display = display;
            long period = Math.clamp(tuning.timeout().toMillis() / 4, 10, 5000);
            watch = WATCHDOG.scheduleWithFixedDelay(this::check, period, period, TimeUnit.MILLISECONDS);
        }

        private void check() {
            if (reading && progress.idleNanos() > tuning.timeout().toNanos()) {
                stalled = true;
                try {
                    in.close();
                } catch (IOException | RuntimeException e) {
                    // the read fails anyway
                }
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            progress.touch(); // the time the reader took is not the server's
            reading = true;
            try {
                return in.read(b, off, len);
            } catch (IOException e) {
                if (stalled) {
                    throw new HttpTimeoutException(message("s3.error.stalled", display, tuning.timeout().toSeconds()));
                }
                throw e;
            } finally {
                reading = false;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] skipped = new byte[(int) Math.min(n, CHUNK)];
            int read = read(skipped, 0, skipped.length);
            return Math.max(read, 0);
        }

        @Override
        public void close() throws IOException {
            watch.cancel(false);
            in.close();
        }
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "copybot-s3-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** The buffer of an upload, grown up to the part's size as the content comes; reused for each part. */
    private static final class PartBuffer {
        byte[] data;

        PartBuffer(int initial) {
            data = new byte[Math.max(initial, 1)];
        }

        /** Reads up to {@code limit} bytes from the start of the buffer; fewer only at the end of the stream. */
        int fill(InputStream in, int limit) throws IOException {
            int count = 0;
            while (count < limit) {
                if (count == data.length) {
                    data = Arrays.copyOf(data, (int) Math.min(limit, Math.max(2L * data.length, CHUNK)));
                }
                int read = in.read(data, count, Math.min(data.length, limit) - count);
                if (read < 0) {
                    break;
                }
                count += read;
            }
            return count;
        }
    }
}
