// @owner BSJ
package com.helpnest.global.error;

import com.helpnest.global.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 {@link ApiResponse} 실패 형식으로 변환한다.
 * 필터 단계(인증 실패 401/403, 요청 제한 429)는 컨트롤러 밖이라 여기로 오지 않는다 → security 쪽에서 같은 형식으로 응답.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode(), e.getMessage());
    }

    /** @Valid 실패: 첫 번째 필드 메시지를 그대로 내려서 프론트가 토스트로 보여 줄 수 있게 한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null && fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : CommonErrorCode.INVALID_INPUT.message();
        return respond(CommonErrorCode.INVALID_INPUT, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        return respond(CommonErrorCode.INVALID_INPUT, CommonErrorCode.INVALID_INPUT.message());
    }

    /** spring.servlet.multipart 한도 초과 (파일당 10MB) */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return respond(CommonErrorCode.INVALID_INPUT, "파일당 10MB 이하만 올릴 수 있습니다.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException e) {
        return respond(CommonErrorCode.NOT_FOUND, CommonErrorCode.NOT_FOUND.message());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(CommonErrorCode.METHOD_NOT_ALLOWED, CommonErrorCode.METHOD_NOT_ALLOWED.message());
    }

    /** 메서드 보안(@PreAuthorize)에서 거부된 경우. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return respond(CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.message());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return respond(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.message());
    }

    private ResponseEntity<ApiResponse<Void>> respond(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.fail(errorCode, message));
    }
}
