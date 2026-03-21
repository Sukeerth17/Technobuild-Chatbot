package in.technobuild.chatbot.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@Slf4j
@ControllerAdvice
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OllamaUnavailableException.class)
    public ResponseEntity<ErrorResponseDto> handleOllamaUnavailable(
            OllamaUnavailableException ex,
            HttpServletRequest request) {
        log.error("Ollama unavailable", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "AI assistant is temporarily unavailable. Please try again in a moment.",
                request.getRequestURI());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponseDto> handleRateLimitExceeded(
            RateLimitExceededException ex,
            HttpServletRequest request) {
        log.error("Rate limit exceeded", ex);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        ErrorResponseDto body = errorBody(HttpStatus.TOO_MANY_REQUESTS,
                "Too many messages. Please wait " + ex.getRetryAfterSeconds() + " seconds.",
                request.getRequestURI());
        return new ResponseEntity<>(body, headers, HttpStatus.TOO_MANY_REQUESTS);
    }

    @ExceptionHandler(TokenBudgetExceededException.class)
    public ResponseEntity<ErrorResponseDto> handleTokenBudgetExceeded(
            TokenBudgetExceededException ex,
            HttpServletRequest request) {
        log.error("Token budget exceeded", ex);
        return build(HttpStatus.TOO_MANY_REQUESTS,
                "You have reached your daily limit. It resets at midnight.",
                request.getRequestURI());
    }

    @ExceptionHandler(SqlValidationException.class)
    public ResponseEntity<ErrorResponseDto> handleSqlValidation(
            SqlValidationException ex,
            HttpServletRequest request) {
        log.error("SQL validation failed. rejectedSql={}", ex.getRejectedSql(), ex);
        return build(HttpStatus.BAD_REQUEST,
                "I cannot process that data request. Please rephrase your question.",
                request.getRequestURI());
    }

    @ExceptionHandler(PiiDetectedException.class)
    public ResponseEntity<ErrorResponseDto> handlePiiDetected(
            PiiDetectedException ex,
            HttpServletRequest request) {
        log.error("PII detected count={}", ex.getPiiCount(), ex);
        return build(HttpStatus.BAD_REQUEST,
                "Your message contained personal information that was removed before processing. Please avoid sharing personal details.",
                request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDto> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        log.error("Access denied", ex);
        return build(HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action.",
                request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        log.error("Validation failed", ex);
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, String.join("; ", messages), request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.error("Invalid request format", ex);
        return build(HttpStatus.BAD_REQUEST,
                "Invalid request format. Please check your request and try again.",
                request.getRequestURI());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponseDto> handleDataAccess(
            DataAccessException ex,
            HttpServletRequest request) {
        log.error("Database error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "A database error occurred. Our team has been notified.",
                request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGeneric(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again or contact support.",
                request.getRequestURI());
    }

    private ResponseEntity<ErrorResponseDto> build(HttpStatus status, String message, String path) {
        return ResponseEntity.status(status).body(errorBody(status, message, path));
    }

    private ErrorResponseDto errorBody(HttpStatus status, String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .build();
    }

    @Data
    @Builder
    public static class ErrorResponseDto {
        private LocalDateTime timestamp;
        private int status;
        private String error;
        private String message;
        private String path;
    }
}
