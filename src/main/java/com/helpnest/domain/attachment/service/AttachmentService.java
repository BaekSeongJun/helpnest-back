// @owner BSJ
package com.helpnest.domain.attachment.service;

import com.helpnest.domain.attachment.dto.AttachmentResponse;
import com.helpnest.domain.attachment.entity.Attachment;
import com.helpnest.domain.attachment.error.AttachmentErrorCode;
import com.helpnest.domain.attachment.repository.AttachmentRepository;
import com.helpnest.global.error.BusinessException;
import com.helpnest.global.error.CommonErrorCode;
import com.helpnest.global.security.JwtProvider;
import com.helpnest.infra.storage.FileStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 첨부 업로드·다운로드 (PRD FR-INQ-02, docs/04 §3) */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    public static final int MAX_ATTACHMENT_COUNT = 5;
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /**
     * 허용 확장자 → 저장·응답에 쓸 Content-Type. 클라이언트가 보낸 MIME 은 위조 가능하므로 쓰지 않는다
     * (".png" 로 올린 HTML 이 text/html 로 내려가지 않게).
     */
    private static final Map<String, String> ALLOWED_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("hwp", "application/x-hwp"),
            Map.entry("hwpx", "application/hwp+zip"));

    private static final String KEY_PREFIX = "attachments";

    private final AttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;

    /** 다운로드 결과: url 이 있으면 302(presigned), 없으면 resource 스트림 */
    public record Download(Attachment attachment, String url, Resource resource) {
    }

    /** @param jwt 비회원이면 null. Guest 토큰도 member 가 아니므로 업로더 NULL */
    public List<AttachmentResponse> upload(List<MultipartFile> files, Jwt jwt) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(AttachmentErrorCode.EMPTY);
        }
        if (files.size() > MAX_ATTACHMENT_COUNT) {
            throw new BusinessException(AttachmentErrorCode.TOO_MANY);
        }
        // 하나라도 잘못되면 아무것도 저장하지 않도록 검증을 먼저 끝낸다
        List<String> contentTypes = files.stream().map(AttachmentService::validate).toList();
        Long uploadedBy = memberIdOrNull(jwt);

        List<Attachment> attachments = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                MultipartFile file = files.get(i);
                attachments.add(Attachment.builder()
                        .originalName(StringUtils.getFilename(StringUtils.cleanPath(file.getOriginalFilename())))
                        .storedKey(fileStorage.upload(file, KEY_PREFIX))
                        .contentType(contentTypes.get(i))
                        .sizeBytes(file.getSize())
                        .uploadedBy(uploadedBy)
                        .build());
            }
            attachmentRepository.saveAll(attachments);   // 한 트랜잭션 → 실패 시 행이 하나도 남지 않음
        } catch (RuntimeException e) {
            // 저장된 파일 본체를 지워 고아 파일을 남기지 않는다
            attachments.forEach(a -> fileStorage.delete(a.getStoredKey()));
            throw e;
        }
        return attachments.stream().map(AttachmentResponse::from).toList();
    }

    public Download download(Long attachmentId, Jwt jwt) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (!canDownload(attachment, jwt)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        String url = fileStorage.getDownloadUrl(attachment.getStoredKey());
        return new Download(attachment, url, url == null ? fileStorage.load(attachment.getStoredKey()) : null);
    }

    /** 업로더 본인 / AGENT+ / 티켓 고객 본인 / Guest 토큰(같은 티켓). 고객·Guest 는 내부 메모 첨부 불가 */
    private boolean canDownload(Attachment a, Jwt jwt) {
        String role = jwt.getClaimAsString(JwtProvider.ROLE_CLAIM);
        if (JwtProvider.GUEST_ROLE.equals(role)) {
            Long guestTicketId = JwtProvider.guestTicketId(jwt);
            return guestTicketId != null && guestTicketId.equals(a.getTicketId()) && !isInternal(a);
        }
        Long memberId = JwtProvider.memberId(jwt);
        if (memberId.equals(a.getUploadedBy()) || !"CUSTOMER".equals(role)) {
            return true;
        }
        return a.getTicketId() != null
                && memberId.equals(attachmentRepository.findTicketCustomerId(a.getTicketId()))
                && !isInternal(a);
    }

    private boolean isInternal(Attachment a) {
        return a.getReplyId() != null && attachmentRepository.isInternalReply(a.getReplyId());
    }

    private static Long memberIdOrNull(Jwt jwt) {
        if (jwt == null || JwtProvider.GUEST_ROLE.equals(jwt.getClaimAsString(JwtProvider.ROLE_CLAIM))) {
            return null;
        }
        return JwtProvider.memberId(jwt);
    }

    /** @return 확장자로 정한 Content-Type */
    private static String validate(MultipartFile file) {
        if (file.isEmpty() || !StringUtils.hasText(file.getOriginalFilename())) {
            throw new BusinessException(AttachmentErrorCode.EMPTY);
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(AttachmentErrorCode.TOO_LARGE);
        }
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        String contentType = ext == null ? null : ALLOWED_TYPES.get(ext.toLowerCase());
        if (contentType == null) {
            throw new BusinessException(AttachmentErrorCode.INVALID_TYPE);
        }
        return contentType;
    }
}
