// @owner SSJ
package com.helpnest.infra.aws;

import static org.assertj.core.api.Assertions.assertThat;

import com.helpnest.infra.mail.MailLog;
import com.helpnest.infra.mail.SesMailTransport;
import com.helpnest.infra.storage.S3FileStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * 실제 AWS 연결 확인 (수동). 비용·외부 자원이 있어 평소 verify·CI 에서는 건너뛴다. 자격 증명은 SDK 기본 체인(~/.aws).
 * <pre>
 * AWS_LIVE=true AWS_REGION=ap-northeast-2 SES_FROM_EMAIL=no-reply@helpnest.kro.kr SES_TEST_TO=me@example.com \
 *   ./mvnw test -Dtest=AwsLiveTest
 * </pre>
 * S3 는 임시 버킷을 만들고 끝나면 객체·버킷을 지운다. SES 샌드박스면 SES_TEST_TO 도 검증된 주소여야 한다.
 */
@EnabledIfEnvironmentVariable(named = "AWS_LIVE", matches = "true")
class AwsLiveTest {

    private static final String REGION = System.getenv().getOrDefault("AWS_REGION", "ap-northeast-2");

    private final AwsConfig config = new AwsConfig(REGION);

    @Test
    @DisplayName("S3: 임시 버킷에 업로드 → presigned URL 로 실제 GET → 삭제")
    void s3RoundTrip() throws Exception {
        String bucket = "helpnest-live-test-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3Client s3 = config.s3Client(); S3Presigner presigner = config.s3Presigner()) {
            // us-east-1 외 리전은 LocationConstraint 필수
            s3.createBucket(b -> b.bucket(bucket).createBucketConfiguration(c -> c.locationConstraint(REGION)));
            String key = null;
            try {
                S3FileStorage storage = new S3FileStorage(s3, presigner, bucket);
                byte[] content = "헬프네스트 S3 연결 확인".getBytes(StandardCharsets.UTF_8);
                key = storage.upload(new MockMultipartFile("f", "live.txt", "text/plain", content), "live");

                HttpResponse<byte[]> res = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(storage.getDownloadUrl(key))).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertThat(res.statusCode()).isEqualTo(200);
                assertThat(res.body()).isEqualTo(content);
            } finally {
                if (key != null) {
                    String uploaded = key;
                    s3.deleteObject(b -> b.bucket(bucket).key(uploaded));
                }
                s3.deleteBucket(b -> b.bucket(bucket));
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SES_TEST_TO", matches = ".+@.+")
    @DisplayName("SES: 실제 메일 1통 발송")
    void sesSend() {
        try (SesV2Client ses = config.sesV2Client()) {
            SesMailTransport transport = new SesMailTransport(ses, System.getenv("SES_FROM_EMAIL"));
            MailLog.Status status = transport.send(System.getenv("SES_TEST_TO"), "[HelpNest] SES 연결 확인",
                    "<p>HelpNest prod 메일 전송(SesMailTransport) 연결 확인 메일입니다.</p>");
            assertThat(status).isEqualTo(MailLog.Status.SENT);
        }
    }
}
