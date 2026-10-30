package com.gonecase.filelocker.config;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Credentials come from the default provider chain (IAM role in AWS, env vars locally). */
@Configuration
public class AwsConfig {

    @Bean
    S3Client s3Client(LockerProperties props) {
        var builder = S3Client.builder().region(Region.of(props.region()));
        if (LockerProperties.isSet(props.endpoint())) {
            builder.endpointOverride(URI.create(props.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(LockerProperties props) {
        var builder = S3Presigner.builder().region(Region.of(props.region()));
        String endpoint = props.effectivePresignEndpoint();
        if (LockerProperties.isSet(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                   .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }
}
