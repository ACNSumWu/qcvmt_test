package com.mtl.qcvmt.exception;

import com.mtl.qcvmt.dto.common.ApiResponse;
import jakarta.persistence.OptimisticLockException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(ex.getMessageKey()));
  }

  @ExceptionHandler(N4ConnectionException.class)
  public ResponseEntity<ApiResponse<Void>> handleN4ConnectionException(N4ConnectionException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.fail("n4_connection_error"));
  }

  @ExceptionHandler(N4QueryException.class)
  public ResponseEntity<ApiResponse<Void>> handleN4QueryException(N4QueryException ex) {
    log.error("N4 query failed", ex);
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(ApiResponse.fail("n4_query_error"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiResponse.fail("access_denied"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + " "
            + (error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()))
        .collect(Collectors.joining("; "));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail(message.isBlank() ? "validation_error" : message));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
    log.warn("Request failed with status={}, reason={}", ex.getStatusCode(), ex.getReason());
    String message = ex.getReason() == null ? "request_failed" : ex.getReason();
    return ResponseEntity.status(ex.getStatusCode())
        .body(ApiResponse.fail(message));
  }

  @ExceptionHandler(OptimisticLockException.class)
  public ResponseEntity<ApiResponse<Void>> handleOptimisticLockException(OptimisticLockException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiResponse.fail("optimistic_lock_conflict"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail("internal_error"));
  }
}
