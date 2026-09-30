// @owner SSJ
package com.helpnest.infra.storage;

import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 로컬 디스크 저장. prod는 S3FileStorage(S3) */
@Component
@Profile("!prod")
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(@Value("${storage.local.dir:./uploads}") String dir) {
        this.baseDir = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String upload(MultipartFile file, String keyPrefix) {
        String key = keyPrefix + "/" + UUID.randomUUID() + extension(file.getOriginalFilename());
        Path target = resolve(key);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return key;
    }

    @Override
    public String getDownloadUrl(String key) {
        return null;
    }

    @Override
    public Resource load(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 경로 탈출(../, 절대 경로) 차단: 정규화 후 baseDir 밖이면 거부
    private Path resolve(String key) {
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir) || path.equals(baseDir)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        return path;
    }

    // 확장자는 영숫자만 허용 (없거나 이상하면 생략)
    private static String extension(String filename) {
        String ext = StringUtils.getFilenameExtension(filename);
        return ext != null && ext.matches("[A-Za-z0-9]{1,10}") ? "." + ext.toLowerCase() : "";
    }
}
