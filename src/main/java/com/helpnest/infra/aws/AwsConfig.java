// @owner SSJ
package com.helpnest.infra.aws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * prod 전용 AWS 클라이언트 (docs/02 §8). 자격 증명은 SDK 기본 체인 — EC2 에서는 IAM Role, 키를 코드·yml 에 두지 않는다.
 * local 프로필에는 이 빈들이 없고 LocalFileStorage·LogMailTransport 가 대신한다.
 */
@Configuration
@Profile("prod")
public class AwsConfig {

    private final Region region;

    public AwsConfig(@Value("${aws.region}") String region) {
        this.region = Region.of(region);
    }

    @Bean(destroyMethod = "close")
    public S3Client s3Client() {
        return S3Client.builder().region(region).build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner() {
        return S3Presigner.builder().region(region).build();
    }

    @Bean(destroyMethod = "close")
    public SesV2Client sesV2Client() {
        return SesV2Client.builder().region(region).build();
    }
}
