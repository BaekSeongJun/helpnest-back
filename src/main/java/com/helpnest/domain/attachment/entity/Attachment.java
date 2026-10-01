// @owner BSJ
package com.helpnest.domain.attachment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 첨부 메타데이터. 파일 본체는 FileStorage(로컬/S3). 티켓·답글·회원은 식별자 참조 (docs/02 §5) */
@Getter
@Entity
@Table(name = "attachment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long ID_MIN = 1L << 52;

    /**
     * 추측 불가 난수 [2^52, 2^53). 비회원 첨부는 업로더가 NULL 이라 ID 를 아는 것이 곧 소유 증명
     * (순번이면 남의 업로드를 자기 티켓에 먼저 연결할 수 있다). 2^53 미만 = JS Number 안전 정수.
     */
    @Id
    @Column(name = "attachment_id")
    private Long id;

    /** 업로드 직후 NULL → AttachmentPort.linkToTicket 으로 연결 */
    private Long ticketId;

    private Long replyId;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, length = 500)
    private String storedKey;

    /** 클라이언트 값이 아니라 확장자로 정한 값 (AttachmentService) */
    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    /** 업로드한 회원. 비회원 NULL */
    private Long uploadedBy;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Builder
    private Attachment(String originalName, String storedKey, String contentType, long sizeBytes, Long uploadedBy) {
        this.originalName = originalName;
        this.storedKey = storedKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.id = ID_MIN + RANDOM.nextLong(ID_MIN);
    }
}
