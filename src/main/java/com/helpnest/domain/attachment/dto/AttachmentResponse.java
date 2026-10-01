// @owner BSJ
package com.helpnest.domain.attachment.dto;

import com.helpnest.domain.attachment.entity.Attachment;

public record AttachmentResponse(Long attachmentId, String originalName, long size) {

    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getOriginalName(), attachment.getSizeBytes());
    }
}
