// @owner SSJ
package com.helpnest.infra.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** 호출자: 백성준(첨부 업로드·다운로드) (docs/02 §5.2) */
public interface FileStorage {

    /** 파일을 저장하고 저장 key(keyPrefix/UUID.확장자)를 반환 */
    String upload(MultipartFile file, String keyPrefix);

    /** 다운로드 URL(prod: presigned URL). 로컬은 null → load 스트림 사용 */
    String getDownloadUrl(String key);

    /** 로컬 스트림 다운로드용 */
    Resource load(String key);

    void delete(String key);
}
