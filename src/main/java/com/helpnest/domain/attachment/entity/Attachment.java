// @owner BSJ
package com.helpnest.domain.attachment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    }
}
