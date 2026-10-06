// @owner SSJ
package com.helpnest.infra.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * prod 첨부 저장 (docs/02 §8, Private 버킷 + presigned URL). 다운로드는 브라우저가 S3 에서 직접 받으므로
 * load 는 쓰이지 않는다(AttachmentService 가 getDownloadUrl != null 이면 302).
 */
@Component
@Profile("prod")
public class S3FileStorage implements FileStorage {

    static final Duration URL_TTL = Duration.ofMinutes(10);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3FileStorage(S3Client s3, S3Presigner presigner, @Value("${storage.s3.bucket}") String bucket) {
        Assert.hasText(bucket, "S3_BUCKET 환경변수가 필요합니다 (prod)");
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public String upload(MultipartFile file, String keyPrefix) {
        String key = FileStorage.newKey(keyPrefix, file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(file.getContentType()).build(),
                    RequestBody.fromInputStream(in, file.getSize()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    @Override
    public String getDownloadUrl(String key) {
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(URL_TTL)
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                        .build())
                .url().toString();
    }

    @Override
    public Resource load(String key) {
        throw new UnsupportedOperationException("prod 는 presigned URL 로 다운로드합니다");
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
