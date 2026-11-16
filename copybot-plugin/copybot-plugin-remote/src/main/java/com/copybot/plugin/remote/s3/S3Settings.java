package com.copybot.plugin.remote.s3;

/** The connection fields of s3.read and s3.write (spec remote §3). */
public interface S3Settings {

    /** The server, "https://host[:port]"; absent: AWS's endpoint of the region, https://s3.REGION.amazonaws.com. */
    String endpoint();

    /** The region of the bucket (us-east-1, eu-west-3...; MinIO: its configured region, often us-east-1); required. */
    String region();

    /** The bucket; required. */
    String bucket();

    /** The access key id; absent with the secret key: anonymous requests (a public bucket). Never in a message. */
    String accessKey();

    /** The secret access key, usually ${secret:name}. Never put in a message or a log; never sent (it signs). */
    String secretKey();

    /** The bucket in the path (https://host/bucket/key, MinIO) instead of the host name (https://bucket.host/key). */
    Boolean pathStyle();
}
