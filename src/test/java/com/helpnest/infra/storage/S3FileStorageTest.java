// @owner SSJ
package com.helpnest.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3FileStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final S3FileStorage storage = new S3FileStorage(s3, presigner, "test-bucket");

    @Test
    @DisplayName("upload: 버킷·key(prefix/UUID.확장자)·content-type·크기로 PutObject")
    void upload() {
        MockMultipartFile file = new MockMultipartFile("f", "사진.PNG", "image/png", new byte[] {1, 2, 3});

        String key = storage.upload(file, "attachments");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(req.capture(), body.capture());
        assertThat(key).matches("attachments/[0-9a-f-]{36}\\.png");
        assertThat(req.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(req.getValue().key()).isEqualTo(key);
        assertThat(req.getValue().contentType()).isEqualTo("image/png");
        assertThat(body.getValue().optionalContentLength()).hasValue(3L);
    }

    @Test
    @DisplayName("getDownloadUrl: 10분짜리 presigned GET")
    void presignedUrl() throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("https://s3.example/k?sig").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        assertThat(storage.getDownloadUrl("attachments/a.png")).isEqualTo("https://s3.example/k?sig");

        ArgumentCaptor<GetObjectPresignRequest> req = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(req.capture());
        assertThat(req.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(10));
        assertThat(req.getValue().getObjectRequest().bucket()).isEqualTo("test-bucket");
        assertThat(req.getValue().getObjectRequest().key()).isEqualTo("attachments/a.png");
    }

    @Test
    @DisplayName("delete: DeleteObject / load: prod 미지원 / 버킷 미설정이면 생성 실패")
    void deleteLoadAndConfig() {
        storage.delete("attachments/a.png");

        verify(s3).deleteObject(DeleteObjectRequest.builder().bucket("test-bucket").key("attachments/a.png").build());
        assertThatThrownBy(() -> storage.load("k")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new S3FileStorage(s3, presigner, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S3_BUCKET");
    }

    @Test
    @DisplayName("newKey: 확장자는 영숫자만, 소문자로")
    void newKey() {
        assertThat(FileStorage.newKey("p", "a.JPG")).matches("p/[0-9a-f-]{36}\\.jpg");
        assertThat(FileStorage.newKey("p", "a.p$p")).matches("p/[0-9a-f-]{36}");
        assertThat(FileStorage.newKey("p", null)).matches("p/[0-9a-f-]{36}");
    }
}
