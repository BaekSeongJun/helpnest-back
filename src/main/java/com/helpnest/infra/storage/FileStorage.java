// @owner SSJ
package com.helpnest.infra.storage;

import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 호출자: 백성준(첨부 업로드·다운로드) (docs/02 §5.2). 구현: local LocalFileStorage / prod S3FileStorage */
public interface FileStorage {

    /** 파일을 저장하고 저장 key(keyPrefix/UUID.확장자)를 반환 */
    String upload(MultipartFile file, String keyPrefix);

    /** 다운로드 URL(prod: presigned URL). 로컬은 null → load 스트림 사용 */
    String getDownloadUrl(String key);

    /** 로컬 스트림 다운로드용 */
    Resource load(String key);

    void delete(String key);

    /** 저장 key = keyPrefix/UUID.확장자. 확장자는 영숫자만 허용(없거나 이상하면 생략) — 구현체 공용 */
    static String newKey(String keyPrefix, String originalFilename) {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        String suffix = ext != null && ext.matches("[A-Za-z0-9]{1,10}") ? "." + ext.toLowerCase() : "";
        return keyPrefix + "/" + UUID.randomUUID() + suffix;
    }
}
