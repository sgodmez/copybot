package com.copybot.plugin.remote.s3;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The signature against the examples AWS publishes for S3 ("Signature Calculations for the Authorization Header:
 * Transferring Payload in a Single Chunk", Amazon S3 API reference): same keys, date, requests and results.
 */
public class SigV4Test {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final Instant TIME = Instant.parse("2013-05-24T00:00:00Z");
    private static final SigV4 SIGNER = new SigV4(ACCESS_KEY, SECRET_KEY, "us-east-1");
    private static final String HOST = "examplebucket.s3.amazonaws.com";

    private static TreeMap<String, String> headers(String... namesAndValues) {
        TreeMap<String, String> headers = new TreeMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    private static String signature(String authorization) {
        return authorization.substring(authorization.indexOf("Signature=") + "Signature=".length());
    }

    @Test
    public void getObject() {
        TreeMap<String, String> headers = headers("host", HOST, "range", "bytes=0-9",
                "x-amz-content-sha256", SigV4.EMPTY_SHA256, "x-amz-date", "20130524T000000Z");
        String canonical = SigV4.canonicalRequest("GET", "/test.txt", "", headers, SigV4.EMPTY_SHA256);
        assertEquals("""
                GET
                /test.txt

                host:examplebucket.s3.amazonaws.com
                range:bytes=0-9
                x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
                x-amz-date:20130524T000000Z

                host;range;x-amz-content-sha256;x-amz-date
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""", canonical);
        assertEquals("""
                AWS4-HMAC-SHA256
                20130524T000000Z
                20130524/us-east-1/s3/aws4_request
                7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972""",
                SIGNER.stringToSign(canonical, TIME));
        String authorization = SIGNER.authorization("GET", "/test.txt", "", headers, SigV4.EMPTY_SHA256, TIME);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", authorization);
    }

    @Test
    public void putObject() {
        byte[] payload = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);
        String hash = SigV4.sha256Hex(payload, 0, payload.length);
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash);
        TreeMap<String, String> headers = headers("date", "Fri, 24 May 2013 00:00:00 GMT", "host", HOST,
                "x-amz-content-sha256", hash, "x-amz-date", "20130524T000000Z",
                "x-amz-storage-class", "REDUCED_REDUNDANCY");
        String uri = "/" + SigV4.uriEncode("test$file.text", true);
        assertEquals("/test%24file.text", uri);
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
                signature(SIGNER.authorization("PUT", uri, "", headers, hash, TIME)));
    }

    @Test
    public void getBucketLifecycle() {
        TreeMap<String, String> headers = headers("host", HOST, "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                "x-amz-date", "20130524T000000Z");
        String query = SigV4.canonicalQuery(Map.of("lifecycle", ""));
        assertEquals("lifecycle=", query);
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
                signature(SIGNER.authorization("GET", "/", query, headers, SigV4.EMPTY_SHA256, TIME)));
    }

    @Test
    public void listObjects() {
        TreeMap<String, String> headers = headers("host", HOST, "x-amz-content-sha256", SigV4.EMPTY_SHA256,
                "x-amz-date", "20130524T000000Z");
        String query = SigV4.canonicalQuery(Map.of("prefix", "J", "max-keys", "2"));
        assertEquals("max-keys=2&prefix=J", query, "sorted by name");
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
                signature(SIGNER.authorization("GET", "/", query, headers, SigV4.EMPTY_SHA256, TIME)));
    }

    @Test
    public void namesAndValuesAreEncodedAsAwsDoes() {
        assertEquals("photos/2024/a%20b%C3%A9%2B%3D%26~-_.jpg", SigV4.uriEncode("photos/2024/a bé+=&~-_.jpg", true));
        assertEquals("a%2Fb%2A", SigV4.uriEncode("a/b*", false));
        assertEquals("continuation-token=1%2F2%3D%3D&delimiter=%2F&list-type=2&prefix=",
                SigV4.canonicalQuery(Map.of("list-type", "2", "prefix", "", "delimiter", "/",
                        "continuation-token", "1/2==")));
        assertEquals("a:b c", SigV4.canonicalRequest("GET", "/", "", headers("a", "  b   c "), "x").split("\n")[3],
                "values trimmed, inner spaces merged");
    }

    @Test
    public void theSignatureNeverShowsTheSecret() {
        assertFalse(SIGNER.toString().contains(SECRET_KEY));
        assertFalse(SIGNER.toString().contains(ACCESS_KEY));
    }
}
