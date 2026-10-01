// @owner BSJ
package com.helpnest.domain.attachment.controller;

import com.helpnest.domain.attachment.dto.AttachmentResponse;
import com.helpnest.domain.attachment.service.AttachmentService;
import com.helpnest.domain.attachment.service.AttachmentService.Download;
import com.helpnest.global.common.ApiResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /** 공개(비회원 포함). multipart 필드명 files (여러 개) */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<AttachmentResponse>> upload(@RequestParam("files") List<MultipartFile> files,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(attachmentService.upload(files, jwt));
    }

    /** prod(S3) 는 presigned URL 로 302, 로컬은 스트림 */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Download download = attachmentService.download(id, jwt);
        if (download.url() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(download.url())).build();
        }
        // filename*=UTF-8''... (RFC 5987) 로 한글 파일명 보존
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.attachment().getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(download.attachment().getContentType()))
                .contentLength(download.attachment().getSizeBytes())
                .body(download.resource());
    }
}
