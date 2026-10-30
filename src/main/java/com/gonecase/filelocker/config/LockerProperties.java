package com.gonecase.filelocker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param bucket     private S3 bucket that stores the files
 * @param kmsKeyId   KMS key (id, ARN or alias) used for server-side encryption
 * @param presignTtl lifetime of download links
 * @param region     AWS region
 * @param endpoint         optional S3 endpoint override (e.g. LocalStack); blank for real AWS
 * @param presignEndpoint  optional host used in download links when clients reach S3 by a
 *                         different address than the server does (e.g. localhost vs container name)
 */
@ConfigurationProperties(prefix = "locker")
public record LockerProperties(String bucket, String kmsKeyId, Duration presignTtl, String region,
                               String endpoint, String presignEndpoint) {

    public String effectivePresignEndpoint() {
        return isSet(presignEndpoint) ? presignEndpoint : endpoint;
    }

    public static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
