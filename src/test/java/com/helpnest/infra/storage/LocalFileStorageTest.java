// @owner SSJ
package com.helpnest.infra.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.helpnest.global.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(tempDir.resolve("uploads").toString());
    }

    @Test
    @DisplayName("upload → load → delete")
    void roundTrip() throws Exception {
        var file = new MockMultipartFile("files", "사진.PNG", "image/png", "hello".getBytes(StandardCharsets.UTF_8));

        String key = storage.upload(file, "attachments");

        assertThat(key).startsWith("attachments/").endsWith(".png");
        assertThat(storage.getDownloadUrl(key)).isNull();
        assertThat(storage.load(key).getContentAsString(StandardCharsets.UTF_8)).isEqualTo("hello");

        storage.delete(key);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("../ 또는 절대 경로 key 는 거부")
    void rejectsPathTraversal() throws Exception {
        Files.writeString(tempDir.resolve("secret.txt"), "secret");

        assertThatThrownBy(() -> storage.load("../secret.txt")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete("a/../../secret.txt")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.load(tempDir.resolve("secret.txt").toString()))
                .isInstanceOf(BusinessException.class);
        var file = new MockMultipartFile("files", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> storage.upload(file, "..")).isInstanceOf(BusinessException.class);

        assertThat(tempDir.resolve("secret.txt")).exists();
    }
}
